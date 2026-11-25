// 原生网络工具：1) 读内核邻居表拿 MAC；2) 自己发 ICMP echo 判活并测 RTT
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
#include <fcntl.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>

#ifdef TEST_STANDALONE
  /* 本地自检用，不需要 JNI */
#else
  #include <jni.h>
#endif

#define MAX_ENTRIES 600
static char g_ip[MAX_ENTRIES][20];
static char g_mac[MAX_ENTRIES][20];
static int g_count;

static void reset(void) { g_count = 0; }

static void add_entry(const char *ip, const char *mac) {
    if (g_count >= MAX_ENTRIES) return;
    for (int i = 0; i < g_count; i++) if (strcmp(g_ip[i], ip) == 0) return;
    snprintf(g_ip[g_count], sizeof(g_ip[0]), "%s", ip);
    snprintf(g_mac[g_count], sizeof(g_mac[0]), "%s", mac);
    g_count++;
}

/* ---------- 邻居表：netlink RTM_GETNEIGH ---------- */

static int dump(int family, int *pkts, int *msgs, int *err) {
    *pkts = 0; *msgs = 0; *err = 0;
    int fd = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);
    if (fd < 0) { *err = -1; return -1; }          /* socket 被拒 */
    *err = errno;

    struct sockaddr_nl sa;
    memset(&sa, 0, sizeof(sa));
    sa.nl_family = AF_NETLINK;
    if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) { *err = -2; close(fd); return -2; } /* bind 被拒 */
    *err = errno;

    struct timeval tv; tv.tv_sec = 2; tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct { struct nlmsghdr nlh; struct ndmsg ndm; } req;
    memset(&req, 0, sizeof(req));
    req.nlh.nlmsg_len = NLMSG_LENGTH(sizeof(struct ndmsg));
    req.nlh.nlmsg_type = RTM_GETNEIGH;
    req.nlh.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    req.nlh.nlmsg_seq = 1;
    req.ndm.ndm_family = (unsigned char)family;

    if (send(fd, &req, req.nlh.nlmsg_len, 0) < 0) { *err = -3; close(fd); return -3; } /* send 被拒 */
    *err = errno;

    static char buf[65536];
    int done = 0;
    while (!done) {
        ssize_t n = recv(fd, buf, sizeof(buf), 0);
        if (n <= 0) { if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK) *err = (int)n; break; }
        *pkts += 1;
        int len = (int)n;
        struct nlmsghdr *nh;
        for (nh = (struct nlmsghdr *)buf; NLMSG_OK(nh, len); nh = NLMSG_NEXT(nh, len)) {
            if (nh->nlmsg_type == NLMSG_DONE) { done = 1; break; }
            if (nh->nlmsg_type == NLMSG_ERROR) { *err = -4; done = 1; break; }
            if (nh->nlmsg_type != RTM_NEWNEIGH) continue;
            *msgs += 1;

            struct ndmsg *ndm = (struct ndmsg *)NLMSG_DATA(nh);
            if (ndm->ndm_family != AF_INET) continue;   /* 跳过 IPv6 等条目 */
            int alen = (int)(nh->nlmsg_len - NLMSG_LENGTH(sizeof(struct ndmsg)));
            struct rtattr *rta = (struct rtattr *)((char *)ndm + NLMSG_ALIGN(sizeof(struct ndmsg)));
            char ip[20] = "";
            char mac[20] = "";

            for (; RTA_OK(rta, alen); rta = RTA_NEXT(rta, alen)) {
                if (rta->rta_type == NDA_DST && RTA_PAYLOAD(rta) >= 4) {
                    struct in_addr ia;
                    memcpy(&ia, RTA_DATA(rta), 4);
                    inet_ntop(AF_INET, &ia, ip, sizeof(ip));
                } else if (rta->rta_type == NDA_LLADDR && RTA_PAYLOAD(rta) >= 6) {
                    unsigned char *m = (unsigned char *)RTA_DATA(rta);
                    snprintf(mac, sizeof(mac), "%02X:%02X:%02X:%02X:%02X:%02X",
                             m[0], m[1], m[2], m[3], m[4], m[5]);
                }
            }
            if (ip[0] && mac[0] && strcmp(mac, "00:00:00:00:00:00") != 0) add_entry(ip, mac);
        }
    }
    close(fd);
    return 0;
}

static int arp_file(void) {
    FILE *f = fopen("/proc/net/arp", "r");
    if (!f) return -1;
    char line[256];
    if (!fgets(line, sizeof(line), f)) { fclose(f); return -2; }
    int n = 0;
    while (fgets(line, sizeof(line), f)) {
        char ip[20], hw[20], flags[8], mask[20], dev[32];
        int type;
        if (sscanf(line, "%19s 0x%x %7s %19s %19s %31s", ip, &type, flags, hw, mask, dev) != 6) continue;
        if (strcmp(hw, "00:00:00:00:00:00") == 0) continue;
        add_entry(ip, hw);
        n++;
    }
    fclose(f);
    return n;
}

#define NT_ERR_NONE    0
#define NT_ERR_SOCKET  -1
#define NT_ERR_BIND    -2
#define NT_ERR_SEND    -3
#define NT_ERR_RECV    -4
static int g_nt_err = 0;
static int g_pkts = 0, g_msgs = 0;

static int neigh_core(void) {
    reset();
    int pk = 0, ms = 0, er = 0;
    dump(AF_UNSPEC, &pk, &ms, &er);
    int fam0_cnt = g_count;
    if (g_count == 0) {
        int pk2 = 0, ms2 = 0, er2 = 0;
        dump(AF_INET, &pk2, &ms2, &er2);
        if (er == 0) er = er2;
        pk += pk2; ms += ms2;
    }
    if (g_count == 0) arp_file();
    g_nt_err = er; g_pkts = pk; g_msgs = ms;
    (void)fam0_cnt;
    return g_count;
}

/* ---------- ICMP echo（SOCK_DGRAM 不需要 CAP_NET_RAW） ---------- */

static unsigned short cksum(const void *data, int len) {
    const unsigned short *p = (const unsigned short *)data;
    unsigned int sum = 0;
    while (len > 1) { sum += *p++; len -= 2; }
    if (len == 1) sum += *(const unsigned char *)p;
    while (sum >> 16) sum = (sum & 0xFFFF) + (sum >> 16);
    return (unsigned short)(~sum);
}

static int g_icmp_err = 0;
static unsigned short gseq = 0;

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* 返回 RTT 毫秒；-1 表示不通；-2 表示本机没有发 ICMP 的权限 */
static int ping_core(const char *ipstr, int timeout_ms) {
    struct in_addr dst;
    if (inet_pton(AF_INET, ipstr, &dst) != 1) return -1;

    int dgram = 1;
    int fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP);
    if (fd < 0) {
        dgram = 0;
        g_icmp_err = errno;
        fd = socket(AF_INET, SOCK_RAW, IPPROTO_ICMP);
        if (fd < 0) { g_icmp_err = errno; return -2; }
    }

    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    unsigned char pkt[64];
    memset(pkt, 0, sizeof(pkt));
    struct icmp_hdr {
        unsigned char type, code;
        unsigned short cksum, id, seq;
    } *hdr = (void *)pkt;
    hdr->type = 8;                     /* echo request */
    hdr->code = 0;
    hdr->id = (unsigned short)(getpid() & 0xFFFF);
    (void)dgram;
    unsigned short myseq = __sync_add_and_fetch(&gseq, 1);   /* 每次 ping 用独立序号 */
    hdr->seq = htons(myseq);
    int i;
    for (i = 8; i < (int)sizeof(pkt); i++) pkt[i] = (unsigned char)(i + myseq);
    hdr->id = htons(hdr->id);
    hdr->cksum = cksum(pkt, sizeof(pkt));

    struct sockaddr_in to;
    memset(&to, 0, sizeof(to));
    to.sin_family = AF_INET;
    to.sin_addr = dst;
    long t0 = now_ms();
    if (sendto(fd, pkt, sizeof(pkt), 0, (struct sockaddr *)&to, sizeof(to)) < 0) {
        g_icmp_err = errno;
        close(fd);
        return -1;
    }

    unsigned char rbuf[512];
    while (1) {
        ssize_t n = recv(fd, rbuf, sizeof(rbuf), 0);
        if (n <= 0) break;
        unsigned char *p = rbuf;
        /* SOCK_RAW 会带 IP 头，SOCK_DGRAM 可能不带 */
        if ((p[0] >> 4) == 4) p += (p[0] & 0x0F) * 4;
        int type = p[0];
        if (type != 0 && type != 8) continue;   /* 只认 echo reply */
        unsigned short seq = (unsigned short)((p[6] << 8) | p[7]);
        if (seq != myseq) continue;      /* 只认本次请求的回包（多线程下 gseq 会被别的线程改掉） */
        if (type == 0) {
            long dt = now_ms() - t0;
            close(fd);
            return (int)(dt < 1 ? 1 : dt);
        }
    }
    close(fd);
    return -1;
}

#ifndef TEST_STANDALONE

static jobjectArray build(JNIEnv *env, const char *diag) {
    jobjectArray arr = (*env)->NewObjectArray(env, g_count + 1,
                        (*env)->FindClass(env, "java/lang/String"), NULL);
    if (arr == NULL) return NULL;
    jstring d = (*env)->NewStringUTF(env, diag);
    (*env)->SetObjectArrayElement(env, arr, 0, d);
    (*env)->DeleteLocalRef(env, d);
    for (int i = 0; i < g_count; i++) {
        char line[48];
        snprintf(line, sizeof(line), "%s %s", g_ip[i], g_mac[i]);
        jstring s = (*env)->NewStringUTF(env, line);
        (*env)->SetObjectArrayElement(env, arr, i + 1, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_zt_wol_NetHelper_neigh(JNIEnv *env, jclass clz) {
    (void)clz;
    neigh_core();
    char diag[200];
    snprintf(diag, sizeof(diag), "#pkts=%d msgs=%d err=%d n=%d",
             g_pkts, g_msgs, g_nt_err, g_count);
    return build(env, diag);
}

/* 返回 RTT ms；-1 不通；-2 无权限 */
JNIEXPORT jint JNICALL
Java_com_zt_wol_NetHelper_ping(JNIEnv *env, jclass clz, jstring ip, jint timeout) {
    (void)clz;
    const char *s = (*env)->GetStringUTFChars(env, ip, NULL);
    if (!s) return -2;
    int r = ping_core(s, (int)timeout);
    (*env)->ReleaseStringUTFChars(env, ip, s);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_zt_wol_NetHelper_pingErr(JNIEnv *env, jclass clz) { (void)env; (void)clz; return g_icmp_err; }

#else
/* 本地自检 main */
int main(int argc, char **argv) {
    if (argc < 2) {
        printf("neigh_core: entries=%d (pkts=%d msgs=%d err=%d)\n",
               neigh_core(), g_pkts, g_msgs, g_nt_err);
        for (int i = 0; i < g_count; i++) printf("  %s %s\n", g_ip[i], g_mac[i]);
        return 0;
    }
    for (int i = 1; i < argc; i++) {
        int r = ping_core(argv[i], 1000);
        printf("ping %-16s -> %d (icmp_errno=%d)\n", argv[i], r, g_icmp_err);
    }
    printf("after scan neigh: entries=%d\n", neigh_core());
    for (int i = 0; i < g_count; i++) printf("  %s %s\n", g_ip[i], g_mac[i]);
    return 0;
}
#endif
