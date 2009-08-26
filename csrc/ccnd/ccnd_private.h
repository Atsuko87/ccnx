/**
 * @file ccnd_private.h
 * 
 * Private definitions for the CCN daemon
 *
 * Data structures are described here so that logging and status
 * routines can be compiled separately.
 *
 */
/*-
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc. All rights reserved.
 */

#ifndef CCND_PRIVATE_DEFINED
#define CCND_PRIVATE_DEFINED

#include <poll.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/types.h>

#include <ccn/ccn_private.h>
#include <ccn/coding.h>
#include <ccn/reg_mgmt.h>
#include <ccn/schedule.h>

/*
 * These are defined in other ccn headers, but the incomplete types suffice
 * for the purposes of this header.
 */
struct ccn_charbuf;
struct ccn_indexbuf;
struct ccn_keystore;
struct hashtb;

/*
 * These are defined in this header.
 */
struct ccnd_handle;
struct face;
struct content_entry;
struct nameprefix_entry;
struct propagating_entry;
struct content_tree_node;
struct ccn_forwarding;

//typedef uint_least64_t ccn_accession_t;
typedef unsigned ccn_accession_t;

/*
 * We pass this handle almost everywhere within ccnd
 */
struct ccnd_handle {
    unsigned char ccnd_id[32];      /**< sha256 digest of our public key */
    struct hashtb *faces_by_fd;     /**< keyed by fd */
    struct hashtb *dgram_faces;     /**< keyed by sockaddr */
    struct hashtb *content_tab;     /**< keyed by initial fragment of ContentObject */
    struct hashtb *nameprefix_tab;  /**< keyed by name prefix components */
    struct hashtb *propagating_tab; /**< keyed by nonce */
    struct ccn_indexbuf *skiplinks; /**< skiplist for content-ordered ops */
    unsigned forward_to_gen;        /**< for forward_to updates */
    unsigned face_gen;              /**< faceid generation number */
    unsigned face_rover;            /**< for faceid allocation */
    unsigned face_limit;            /**< current number of face slots */
    struct face **faces_by_faceid;  /**< array with face_limit elements */
    struct ccn_scheduled_event *reaper;
    struct ccn_scheduled_event *age;
    struct ccn_scheduled_event *clean;
    struct ccn_scheduled_event *age_forwarding;
    const char *portstr;            /**< "main" port number */
    int local_listener_fd;          /**< listener for unix-domain connections */
    int tcp4_fd;                    /**< listener for IPv4 tcp connections */
    int tcp6_fd;                    /**< listener for IPv6 tcp connections */
    int udp4_fd;                    /**< common fd for IPv4 unicast */
    int udp6_fd;                    /**< common fd for IPv6 unicast */
    nfds_t nfds;                    /**< number of entries in fds array */
    struct pollfd *fds;             /**< used for poll system call */
    struct ccn_gettime ticktock;    /**< our time generator */
    struct ccn_schedule *sched;     /**< our schedule */
    struct ccn_charbuf *scratch_charbuf; /**< one-slot scratch cache */
    struct ccn_indexbuf *scratch_indexbuf; /**< one-slot scratch cache */
    /** Next three fields are used for direct accession-to-content table */
    ccn_accession_t accession_base;
    unsigned content_by_accession_window;
    struct content_entry **content_by_accession;
    /** The following holds stragglers that would otherwise bloat the above */
    struct hashtb *sparse_straggler_tab; /* keyed by accession */
    ccn_accession_t accession;      /**< newest used accession number */
    ccn_accession_t min_stale;      /**< smallest accession of stale content */
    ccn_accession_t max_stale;      /**< largest accession of stale content */
    unsigned long capacity;         /**< may toss content if #items > capacity */
    unsigned long oldformatcontent;
    unsigned long oldformatcontentgrumble;
    unsigned long oldformatinterests;
    unsigned long oldformatinterestgrumble;
    unsigned long content_dups_recvd;
    unsigned long content_items_sent;
    unsigned long interests_accepted;
    unsigned long interests_dropped;
    unsigned long interests_sent;
    unsigned long interests_stuffed;
    unsigned short seed[3];         /**< for PRNG */
    int debug;                      /**< For controlling debug output */
    int logbreak;                   /**< see ccn_msg() */
    unsigned long logtime;          /**< see ccn_msg() */
    int mtu;                        /**< Target size for stuffing interests */
    int flood;                      // XXX - Temporary, for transition period
    unsigned interest_faceid;       /**< for self_reg internal client */
    const char *progname;           /**< our name, for locating helpers */
    struct ccn *internal_client;    /**< internal client */
    struct ccn_keystore *internal_keys; /**< the internal client's keys */
    struct face *face0;             /**< special face for internal client */
    struct ccn_scheduled_event *internal_client_refresh;
    unsigned data_pause_microsec;   /**< tunable, see choose_face_delay() */
};

/**
 * Each face is referenced by a number, the faceid.  The low-order
 * bits (under the MAXFACES) constitute a slot number that is
 * unique (for this ccnd) among the faces that are alive at a given time.
 * The rest of the bits form a generation number that make the
 * entire faceid unique over time, even for faces that are defunct.
 */
#define FACESLOTBITS 18
#define MAXFACES ((1U << FACESLOTBITS) - 1)

struct content_queue {
    unsigned usec;                   /**< mean delay for this queue */
    unsigned ready;                  /**< # that have waited enough */
    struct ccn_indexbuf *send_queue; /**< accession numbers of pending content */
    struct ccn_scheduled_event *sender;
};

enum cq_delay_class {
    CCN_CQ_ASAP,
    CCN_CQ_NORMAL,
    CCN_CQ_SLOW,
    CCN_CQ_N
};

/**
 * One of our active interfaces
 */
struct face {
    int recv_fd;                /**< socket for receiving */
    int send_fd;                /**< socket for sending (maybe == recv_fd) */
    int flags;                  /**< CCN_FACE_* face flags */
    unsigned faceid;            /**< internal face id */
    unsigned recvcount;         /**< for activity level monitoring */
    struct content_queue *q[CCN_CQ_N]; /**< outgoing content, per delay class */
    struct ccn_charbuf *inbuf;
    struct ccn_skeleton_decoder decoder;
    size_t outbufindex;
    struct ccn_charbuf *outbuf;
    const struct sockaddr *addr;
    socklen_t addrlen;
    int pending_interests;
};

/** face flags */
#define CCN_FACE_LINK   (1 << 0) /**< Elements wrapped by CCNProtocolDataUnit */
#define CCN_FACE_DGRAM  (1 << 1) /**< Datagram interface, respect packets */
#define CCN_FACE_GG     (1 << 2) /**< Considered friendly */
#define CCN_FACE_LOCAL  (1 << 3) /**< PF_UNIX socket */
#define CCN_FACE_INET   (1 << 4) /**< IPv4 */
#define CCN_FACE_MCAST  (1 << 5) /**< a party line (e.g. multicast) */
#define CCN_FACE_INET6  (1 << 6) /**< IPv6 */
#define CCN_FACE_DC     (1 << 7) /**< Face sends Inject messages */
#define CCN_FACE_NOSEND (1 << 8) /**< Don't send anymore */
#define CCN_FACE_UNDECIDED (1 << 9) /**< Might not be talking ccn */
#define CCN_FACE_PERMANENT (1 << 10) /**< No timeout for inactivity */

/**
 *  The content hash table is keyed by the initial portion of the ContentObject
 *  that contains all the parts of the complete name.  The extdata of the hash
 *  table holds the rest of the object, so that the whole ContentObject is
 *  stored contiguously.  The internal form differs from the on-wire form in
 *  that the final content-digest name component is represented explicitly,
 *  which simplifies the matching logic.
 *  The original ContentObject may be reconstructed simply by excising this
 *  last name component, which is easily located via the comps array.
 */
struct content_entry {
    ccn_accession_t accession;  /**< assigned in arrival order */
    unsigned short *comps;      /**< Name Component byte boundary offsets */
    int ncomps;                 /**< Number of name components plus one */
    int flags;                  /**< see below */
    const unsigned char *key;	/**< ccnb-encoded ContentObject */
    int key_size;               /**< Size of fragment prior to Content */
    int size;                   /**< Size of ContentObject */
    struct ccn_indexbuf *skiplinks; /**< skiplist for name-ordered ops */
};
/**
 * content_entry flags
 */
#define CCN_CONTENT_ENTRY_SLOWSEND  1
#define CCN_CONTENT_ENTRY_STALE     2
#define CCN_CONTENT_ENTRY_PRECIOUS  4

/**
 * The sparse_straggler hash table, keyed by accession, holds scattered
 * entries that would otherwise bloat the direct content_by_accession table.
 */
struct sparse_straggler_entry {
    struct content_entry *content;
};

/**
 * The nameprefix hash table is keyed by the Component elements of
 * the Name prefix.
 */
struct nameprefix_entry {
    struct propagating_entry *propagating_head;
    struct ccn_indexbuf *forward_to; /**< faceids to forward to */
    struct ccn_forwarding *forwarding; /**< detailed forwarding info */
    struct nameprefix_entry *parent; /**< link to next-shorter prefix */
    int children;                /**< number of children */
    int fgen;                    /**< used to decide when forward_to is stale */
    unsigned src;                /**< faceid of recent matching content */
    unsigned osrc;               /**< and of older matching content */
    unsigned usec;               /**< response-time prediction */
};

/**
 * Keeps track of the faces that interests matching a given name prefix may be
 * forwarded to.
 */
struct ccn_forwarding {
    unsigned faceid;
    unsigned flags;              /**< CCN_FORW_* - c.f. <ccn/reg_mgnt.h> */
    int expires;                 /**< time remaining, in seconds */
    struct ccn_forwarding *next;
};

/**
 * @def CCN_FORW_ACTIVE         1
 * @def CCN_FORW_CHILD_INHERIT  2
 * @def CCN_FORW_ADVERTISE      4
 */
#define CCN_FORW_PUBMASK (CCN_FORW_ACTIVE        | \
                          CCN_FORW_CHILD_INHERIT | \
                          CCN_FORW_ADVERTISE       )
#define CCN_FORW_REFRESHED      (1 << 16) /**< private to ccnd */
 
/**
 * Determines how frequently we age our fowarding entries
 */
#define CCN_FWU_SECS 5

/**
 * The propagating interest hash table is keyed by Nonce.
 */
struct propagating_entry {
    struct propagating_entry *next;
    struct propagating_entry *prev;
    struct ccn_indexbuf *outbound;
    unsigned char *interest_msg;
    unsigned size;              /* size in bytes of interest_msg */
    unsigned flags;             /* CCN_PR_xxx */
    unsigned faceid;            /* origin of the interest, dest for matches */
    int usec;                   /* usec until timeout */
};
#define CCN_PR_UNSENT 1  /* interest has not been sent anywhere yet */
#define CCN_PR_WAIT1  2  /* interest has been sent to one place */
#define CCN_PR_STUFFED1 4 /* was stuffed before sent anywhere else */

/*
 * Internal client
 * The internal client is for communication between the ccnd and other
 * components, using (of course) ccn protocols.
 */
int ccnd_init_internal_keystore(struct ccnd_handle *);
int ccnd_internal_client_start(struct ccnd_handle *);
void ccnd_internal_client_stop(struct ccnd_handle *);

/**
 * The internal client calls this with the argument portion ARG of
 * a self-registration request (/ccn/reg/self/ARG)
 * The result, if not NULL, will be used as the Content of the reply.
 */
struct ccn_charbuf *ccnd_reg_self(struct ccnd_handle *h,
                                  const unsigned char *msg, size_t size);

/**
 * The internal client calls this with the argument portion ARG of
 * a face-creation request (/ccn/CCNDID/newface/ARG)
 * The result, if not NULL, will be used as the Content of the reply.
 */
struct ccn_charbuf *ccnd_req_newface(struct ccnd_handle *h,
                                     const unsigned char *msg, size_t size);

/**
 * The internal client calls this with the argument portion ARG of
 * a prefix-registration request (/ccn/CCNDID/prefixreg/ARG)
 * The result, if not NULL, will be used as the Content of the reply.
 */
struct ccn_charbuf *ccnd_req_prefixreg(struct ccnd_handle *h,
                                       const unsigned char *msg, size_t size);


int ccnd_reg_prefix(struct ccnd_handle *h,
                    const unsigned char *msg,
                    struct ccn_indexbuf *comps,
                    int ncomps,
                    unsigned faceid,
                    int flags,
                    int expires);

int ccnd_reg_uri(struct ccnd_handle *h,
                 const char *uri,
                 unsigned faceid,
                 int flags,
                 int expires);

/* Consider a separate header for these */
int ccnd_stats_handle_http_connection(struct ccnd_handle *, struct face *);
void ccnd_msg(struct ccnd_handle *, const char *, ...);
void ccnd_debug_ccnb(struct ccnd_handle *h,
                     int lineno,
                     const char *msg,
                     struct face *face,
                     const unsigned char *ccnb,
                     size_t ccnb_size);
void shutdown_client_fd(struct ccnd_handle *h, int fd);

#endif
