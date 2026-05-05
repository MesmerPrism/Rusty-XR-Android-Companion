#pragma once

#include <stdint.h>

#if defined(_WIN32) || defined(__CYGWIN__)
#define LIBLSL_C_API
#else
#define LIBLSL_C_API __attribute__((visibility("default")))
#endif

typedef struct lsl_streaminfo_struct_ *lsl_streaminfo;
typedef struct lsl_inlet_struct_ *lsl_inlet;

typedef enum {
    proc_none = 0,
    proc_clocksync = 1,
    proc_dejitter = 2,
    proc_monotonize = 4,
    proc_threadsafe = 8,
    proc_ALL = 1 | 2 | 4 | 8,
    _proc_maxval = 0x7f000000
} lsl_processing_options_t;

typedef enum {
    lsl_no_error = 0,
    lsl_timeout_error = -1,
    lsl_lost_error = -2,
    lsl_argument_error = -3,
    lsl_internal_error = -4,
    _lsl_error_code_maxval = 0x7f000000
} lsl_error_code_t;

extern "C" {
LIBLSL_C_API const char *lsl_last_error(void);
LIBLSL_C_API const char *lsl_library_info(void);
LIBLSL_C_API int32_t lsl_resolve_byprop(
    lsl_streaminfo *buffer,
    uint32_t buffer_elements,
    const char *prop,
    const char *value,
    int32_t minimum,
    double timeout
);
LIBLSL_C_API void lsl_destroy_streaminfo(lsl_streaminfo info);
LIBLSL_C_API const char *lsl_get_name(lsl_streaminfo info);
LIBLSL_C_API const char *lsl_get_type(lsl_streaminfo info);
LIBLSL_C_API int32_t lsl_get_channel_count(lsl_streaminfo info);
LIBLSL_C_API double lsl_get_nominal_srate(lsl_streaminfo info);
LIBLSL_C_API lsl_inlet lsl_create_inlet(
    lsl_streaminfo info,
    int32_t max_buflen,
    int32_t max_chunklen,
    int32_t recover
);
LIBLSL_C_API void lsl_destroy_inlet(lsl_inlet in);
LIBLSL_C_API void lsl_open_stream(lsl_inlet in, double timeout, int32_t *ec);
LIBLSL_C_API int32_t lsl_set_postprocessing(lsl_inlet in, uint32_t flags);
LIBLSL_C_API double lsl_pull_sample_f(
    lsl_inlet in,
    float *buffer,
    int32_t buffer_elements,
    double timeout,
    int32_t *ec
);
}
