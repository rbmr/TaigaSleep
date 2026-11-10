package api

import (
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/pocketbase/pocketbase/tools/types"
)

// Server limits. They are configuration, not part of the contract: a request over a
// limit is answered with 413 and a code, and the backend README lists the variables.
const (
	defaultMaxRecordingMB               = 256
	defaultMaxRecordingFileMB           = 64
	defaultMaxUncompressedMB            = 512
	defaultMaxUncompressedFileMB        = 128
	defaultMaxUploadChunkMB             = 8
	defaultMaxUploadUncompressedChunkMB = 16
	defaultMaxMetaKB                    = 64
	defaultUserQuotaGB                  = 10
	maxUploadMembers                    = 10000
	maxExpansionRatio                   = 200.0
)

func envInt(name string, fallback int64) int64 {
	value := os.Getenv(name)
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

// userQuotaBytes is the stored bytes one account may hold across all of its
// recordings. TAIGA_USER_QUOTA_GB sets it in GB; 0 removes the limit; unset, negative
// or not a whole number means the default.
func userQuotaBytes() int64 {
	value := strings.TrimSpace(os.Getenv("TAIGA_USER_QUOTA_GB"))
	if value == "" {
		return defaultUserQuotaGB * 1024 * 1024 * 1024
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed < 0 {
		return defaultUserQuotaGB * 1024 * 1024 * 1024
	}
	return parsed * 1024 * 1024 * 1024
}

func maxTotalBytes() int64 {
	return envInt("TAIGA_MAX_RECORDING_MB", defaultMaxRecordingMB) * 1024 * 1024
}
func maxFileBytes() int64 {
	return envInt("TAIGA_MAX_RECORDING_FILE_MB", defaultMaxRecordingFileMB) * 1024 * 1024
}
func maxUncompressedBytes() int64 {
	return envInt("TAIGA_MAX_RECORDING_UNCOMPRESSED_MB", defaultMaxUncompressedMB) * 1024 * 1024
}
func maxUncompressedFileBytes() int64 {
	return envInt("TAIGA_MAX_RECORDING_UNCOMPRESSED_FILE_MB", defaultMaxUncompressedFileMB) * 1024 * 1024
}
func maxChunkBytes() int64 {
	return envInt("TAIGA_MAX_UPLOAD_CHUNK_MB", defaultMaxUploadChunkMB) * 1024 * 1024
}
func maxUncompressedChunkBytes() int64 {
	return envInt("TAIGA_MAX_UPLOAD_UNCOMPRESSED_CHUNK_MB", defaultMaxUploadUncompressedChunkMB) * 1024 * 1024
}
func maxMetaBytes() int64 { return envInt("TAIGA_MAX_META_KB", defaultMaxMetaKB) * 1024 }
func maxMembers() int     { return int(envInt("TAIGA_MAX_UPLOAD_MEMBERS", maxUploadMembers)) }

// PocketBase stores dates as "2006-01-02 15:04:05.000Z" text, which sorts and
// compares correctly as a string. The API speaks RFC 3339.
const pbDateLayout = "2006-01-02 15:04:05.000Z"

func nowString() string { return types.NowDateTime().String() }

func pbDateMillis(ms int64) string { return time.UnixMilli(ms).UTC().Format(pbDateLayout) }

func afterSeconds(seconds int) string {
	return pbDateMillis(time.Now().Add(time.Duration(seconds) * time.Second).UnixMilli())
}

// rfc3339 converts a stored date to the API's format, or nil when there is none.
func rfc3339(stored string) *string {
	if stored == "" {
		return nil
	}
	parsed, err := time.Parse(pbDateLayout, stored)
	if err != nil {
		return nil
	}
	value := parsed.UTC().Format(time.RFC3339)
	return &value
}
