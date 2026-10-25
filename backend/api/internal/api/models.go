package api

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
)

const (
	metaFileName   = "meta.json"
	eventsFileName = "events.jsonl"
	hrFileName     = "hr.log"
	ecgFileName    = "ecg.log"
	accFileName    = "acc.log"
)

var (
	sessionIDRE = regexp.MustCompile(`^[A-Za-z0-9]{1,32}$`)
	clientIDRE  = regexp.MustCompile(`^[A-Za-z0-9_-]{1,40}$`)
	modelIDRE   = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,63}$`)
)

var (
	logFiles = []string{hrFileName, ecgFileName, accFileName}
	allFiles = []string{metaFileName, eventsFileName, hrFileName, ecgFileName, accFileName}
	states   = map[string]bool{
		"recording":   true,
		"stopped":     true,
		"forced_stop": true,
		"interrupted": true,
	}
)

type RecordingMeta struct {
	Format    string `json:"format"`
	Version   int    `json:"version"`
	SessionID string `json:"session_id"`
	State     string `json:"state"`
	StartUTC  int64  `json:"start_utc_ms"`
	EndUTC    *int64 `json:"end_utc_ms"`
	Timezone  string `json:"timezone"`
	App       struct {
		Version     string `json:"version"`
		VersionCode int    `json:"version_code"`
	} `json:"app"`
	Phone struct {
		Manufacturer string `json:"manufacturer"`
		Model        string `json:"model"`
		Android      string `json:"android"`
		SDK          int    `json:"sdk"`
	} `json:"phone"`
	Sensor struct {
		Kind           string `json:"kind"`
		ID             string `json:"id"`
		Name           string `json:"name"`
		Firmware       string `json:"firmware"`
		BatteryPercent int    `json:"battery_percent_at_start"`
	} `json:"sensor"`
	Config struct {
		HR        *bool    `json:"hr"`
		ECG       *bool    `json:"ecg"`
		ACC       *bool    `json:"acc"`
		ECGRateHz *float64 `json:"ecg_rate_hz"`
		ACCRateHz *float64 `json:"acc_rate_hz"`
		ACCRangeG *float64 `json:"acc_range_g"`
	} `json:"config"`
}

func ValidateMeta(raw []byte) (RecordingMeta, error) {
	var meta RecordingMeta
	if err := json.Unmarshal(raw, &meta); err != nil {
		return meta, fmt.Errorf("meta is not valid JSON: %w", err)
	}
	if meta.Format != "taiga-recording" {
		return meta, fmt.Errorf("meta.format must be 'taiga-recording'")
	}
	if meta.Version != 1 {
		return meta, fmt.Errorf("unsupported meta.version")
	}
	if !clientIDRE.MatchString(meta.SessionID) {
		return meta, fmt.Errorf("meta.session_id is invalid")
	}
	if !states[meta.State] {
		return meta, fmt.Errorf("meta.state is invalid")
	}
	if meta.StartUTC <= 0 {
		return meta, fmt.Errorf("meta.start_utc_ms is invalid")
	}
	if meta.EndUTC != nil && *meta.EndUTC < meta.StartUTC {
		return meta, fmt.Errorf("meta.end_utc_ms is invalid")
	}
	if meta.EndUTC == nil && meta.State != "recording" {
		return meta, fmt.Errorf("meta.end_utc_ms is required for a finished recording")
	}
	if len(meta.Timezone) > 64 {
		return meta, fmt.Errorf("meta.timezone is invalid")
	}
	if strings.TrimSpace(meta.Sensor.Kind) == "" {
		return meta, fmt.Errorf("meta.sensor.kind is required")
	}
	if meta.Config.HR == nil || meta.Config.ECG == nil || meta.Config.ACC == nil {
		return meta, fmt.Errorf("meta.config.hr/ecg/acc must be booleans")
	}
	if meta.Config.ECGRateHz == nil || *meta.Config.ECGRateHz <= 0 {
		return meta, fmt.Errorf("meta.config.ecg_rate_hz must be a positive number")
	}
	if meta.Config.ACCRateHz == nil || *meta.Config.ACCRateHz <= 0 {
		return meta, fmt.Errorf("meta.config.acc_rate_hz must be a positive number")
	}
	return meta, nil
}

func (m RecordingMeta) Device() string {
	if strings.TrimSpace(m.Sensor.Name) != "" {
		return m.Sensor.Name
	}
	return m.Sensor.Kind
}

func (m RecordingMeta) DurationSeconds() int64 {
	if m.EndUTC == nil {
		return 0
	}
	value := (*m.EndUTC - m.StartUTC) / 1000
	if value < 0 {
		return 0
	}
	return value
}
