/*
 * ccnd_stats.c
 *  
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc. All rights reserved.
 */

/**
 * @file Statistics presentation for ccnd
 */

#include <sys/types.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <sys/utsname.h>
#include <time.h>
#include <unistd.h>

#if defined(NEED_GETADDRINFO_COMPAT)
    #include "getaddrinfo.h"
#endif

#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/hashtb.h>
#include <ccn/uri.h>

#include "ccnd_private.h"

#define CRLF "\r\n"

struct ccnd_stats {
    long total_interest_counts;
    long total_flood_control;      /* done propagating, still recorded */
};

int
ccnd_collect_stats(struct ccnd *h, struct ccnd_stats *ans)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    long sum;
    unsigned i;
    for (sum = 0, hashtb_start(h->nameprefix_tab, e);
                                         e->data != NULL; hashtb_next(e)) {
        struct nameprefix_entry *ipe = e->data;
        struct propagating_entry *head = ipe->propagating_head;
        struct propagating_entry *p;
        if (head != NULL) {
            for (p = head->next; p != head; p = p->next) {
                sum += 1;
            }
        }
    }
    ans->total_interest_counts = sum;
    hashtb_end(e);
    for (sum = 0, hashtb_start(h->propagating_tab, e);
                                      e->data != NULL; hashtb_next(e)) {
        struct propagating_entry *pi = e->data;
        if (pi->interest_msg == NULL)
            sum += 1;
    }
    ans->total_flood_control = sum;
    hashtb_end(e);
    /* Do a consistency check on pending interest counts */
    for (sum = 0, i = 0; i < h->face_limit; i++) {
        struct face *face = h->faces_by_faceid[i];
        if (face != NULL)
            sum += face->pending_interests;
    }
    if (sum != ans->total_interest_counts)
        ccnd_msg(h, "ccnd_collect_stats found inconsistency %ld != %ld\n",
            (long)sum, (long)ans->total_interest_counts);
    return(0);
}

static void
collect_faces_html(struct ccnd *h, struct ccn_charbuf *b)
{
    int i;
    char node[104];
    char port[8];
    int res;
    int niflags = 0;
    
    ccn_charbuf_putf(b, "<h4>Faces</h4>");
    ccn_charbuf_putf(b, "<ul>");
    for (i = 0; i < h->face_limit; i++) {
        struct face *face = h->faces_by_faceid[i];
        if (face != NULL && (face->flags & CCN_FACE_UNDECIDED) == 0) {
            ccn_charbuf_putf(b, "<li>");
            ccn_charbuf_putf(b, " <b>face:</b> %u <b>flags:</b> 0x%x",
                             face->faceid, face->flags);
            ccn_charbuf_putf(b, " <b>pending:</b> %d",
                             face->pending_interests);
            if (face->recvcount != 0)
                ccn_charbuf_putf(b, " <b>activity:</b> %d",
                                 face->recvcount);
            if (face->addr != NULL) {
                niflags = NI_NUMERICHOST | NI_NUMERICSERV;
                if ((face->flags & CCN_FACE_DGRAM) != 0)
                    niflags |= NI_DGRAM;
                res = getnameinfo(face->addr, face->addrlen,
                    node, sizeof(node),
                    port, sizeof(port),
                    niflags
                    );
                if (res == 0)
                    ccn_charbuf_putf(b, " <b>remote:</b> [%s]:%s",
                                     node, port);
            }
            ccn_charbuf_putf(b, "</li>");
        }
    }
    ccn_charbuf_putf(b, "</ul>");
}

static void
collect_forwarding_html(struct ccnd *h, struct ccn_charbuf *b)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_forwarding *f;
    int res;
    struct ccn_charbuf *name = ccn_charbuf_create();
    
    ccn_charbuf_putf(b, "<h4>Forwarding</h4>");
    ccn_charbuf_putf(b, "<ul>");
    hashtb_start(h->nameprefix_tab, e);
    for (; e->data != NULL; hashtb_next(e)) {
        struct nameprefix_entry *ipe = e->data;
        ccn_name_init(name);
        res = ccn_name_append_components(name, e->key, 0, e->keysize);
        if (res < 0)
            abort();
        if (0) {
            ccn_charbuf_putf(b, "<li>");
            ccn_uri_append(b, name->buf, name->length, 1);
            ccn_charbuf_putf(b, "</li>");
        }
        for (f = ipe->forwarding; f != NULL; f = f->next) {
            if ((f->flags & CCN_FORW_ACTIVE) != 0) {
                ccn_name_init(name);
                res = ccn_name_append_components(name, e->key, 0, e->keysize);
                ccn_charbuf_putf(b, "<li>");
                ccn_uri_append(b, name->buf, name->length, 1);
                ccn_charbuf_putf(b, " <b>face:</b> %u <b>flags:</b> 0x%x <b>expires:</b> %d",
                                 f->faceid, f->flags, f->expires);
                ccn_charbuf_putf(b, "</li>");
            }
        }
    }
    hashtb_end(e);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_putf(b, "</ul>");
}

static char *
collect_stats_html(struct ccnd *h)
{
    char *ans;
    struct ccnd_stats stats = {0};
    struct ccn_charbuf *b = ccn_charbuf_create();
    int pid;
    struct utsname un;
    const char *portstr;
    
    portstr = getenv(CCN_LOCAL_PORT_ENVNAME);
    if (portstr == NULL || portstr[0] == 0 || strlen(portstr) > 10)
        portstr = "4485";
    uname(&un);
    pid = getpid();
    
    ccnd_collect_stats(h, &stats);
    ccn_charbuf_putf(b,
        "<html>"
        "<head>"
        "<title>ccnd[%d]</title>"
        //"<meta http-equiv='refresh' content='3'>"
        "<style type='text/css'>"
        " p.header {color: white; background-color: blue} "
        "</style>"
        "</head>"
        "<body>"
        "<p class='header' width='100%%'>%s ccnd[%d] local port %s</p>"
        "<div><b>Content items:</b> %llu accessioned, %d stored, %d sparse, %lu duplicate, %lu sent</div>"
        "<div><b>Interests:</b> %d names, %ld pending, %ld propagating, %ld noted</div>"
        "<div><b>Interest totals:</b> %lu accepted, %lu dropped, %lu sent, %lu stuffed</div>",
        pid,
        un.nodename,
        pid,
        portstr,
        (unsigned long long)h->accession,
        hashtb_n(h->content_tab),
        hashtb_n(h->sparse_straggler_tab),
        h->content_dups_recvd,
        h->content_items_sent,
        hashtb_n(h->nameprefix_tab), stats.total_interest_counts,
        hashtb_n(h->propagating_tab) - stats.total_flood_control,
        stats.total_flood_control,
        h->interests_accepted, h->interests_dropped,
        h->interests_sent, h->interests_stuffed);
    if (0)
        ccn_charbuf_putf(b,
                         "<div><b>Active faces and listeners:</b> %d</div>",
                         hashtb_n(h->faces_by_fd) + hashtb_n(h->dgram_faces));
    collect_faces_html(h, b);
    collect_forwarding_html(h, b);
    ccn_charbuf_putf(b,
        "</body>"
        "</html>");
    ans = strdup((char *)b->buf);
    ccn_charbuf_destroy(&b);
    return(ans);
}

static const char *resp404 =
    "HTTP/1.1 404 Not Found" CRLF
    "Connection: close" CRLF CRLF;

static const char *resp405 =
    "HTTP/1.1 405 Method Not Allowed" CRLF
    "Connection: close" CRLF CRLF;

int
ccnd_stats_handle_http_connection(struct ccnd *h, struct face *face)
{
    int res;
    int hdrlen;
    char *response = NULL;
    struct linger linger = { .l_onoff = 1, .l_linger = 1 };
    int fd = face->fd;
    char buf[128];
    
    if (face->inbuf->length < 6)
        return(-1);
    response = collect_stats_html(h);
    /* Set linger to prevent quickly resetting the connection on close.*/
    res = setsockopt(face->fd, SOL_SOCKET, SO_LINGER, &linger, sizeof(linger));
    if (0 == memcmp(face->inbuf->buf, "GET / ", 6)) {
        res = strlen(response);
        hdrlen = snprintf(buf, sizeof(buf),
                          "HTTP/1.1 200 OK" CRLF
                          "Content-Type: text/html; charset=utf-8" CRLF
                          "Connection: close" CRLF
                          "Content-Length: %d" CRLF CRLF,
                          res);
        (void)write(fd, buf, hdrlen);
        (void)write(fd, response, res);
    }
    else if (0 == memcmp(buf, "GET ", 4))
        (void)write(fd, resp404, strlen(resp404));
    else
        (void)write(fd, resp405, strlen(resp405));
    shutdown_client_fd(h, face->fd);
    free(response);
    return(0);
}
