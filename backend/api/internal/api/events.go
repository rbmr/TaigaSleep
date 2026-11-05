package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/pocketbase/pocketbase/core"
)

// The event stream tells a connected client that something it shows has changed. An
// event carries ids and a status, never data, so a client that missed one (it was not
// connected, or fell behind) loses nothing but a refetch. There is no replay: a
// client treats a (re)connect as "refetch what I show".

const (
	eventAnalysis  = "analysis"
	eventRecording = "recording"

	// subscriberBuffer is how many events a client may fall behind by. Past that its
	// connection is closed, so it reconnects and refetches instead of reading a stale tail.
	subscriberBuffer = 64
)

// eventKeepalive is how often a comment line keeps idle connections open through proxies.
var eventKeepalive = 20 * time.Second

// AnalysisEvent says an analysis was created, changed or deleted. Status is the
// analysis status, or "deleted".
type AnalysisEvent struct {
	ID        string `json:"id"`
	Recording string `json:"recording"`
	Status    string `json:"status"`
}

// RecordingEvent says a recording was created, changed or deleted.
type RecordingEvent struct {
	ID     string `json:"id"`
	Change string `json:"change"`
}

type eventMessage struct {
	name string
	data []byte
}

type subscriber struct {
	role    string
	account string
	ch      chan eventMessage
}

type eventHub struct {
	mu   sync.Mutex
	subs map[*subscriber]struct{}
}

var events = &eventHub{subs: map[*subscriber]struct{}{}}

func (h *eventHub) subscribe(role, account string) *subscriber {
	sub := &subscriber{role: role, account: account, ch: make(chan eventMessage, subscriberBuffer)}
	h.mu.Lock()
	h.subs[sub] = struct{}{}
	h.mu.Unlock()
	return sub
}

func (h *eventHub) unsubscribe(sub *subscriber) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if _, ok := h.subs[sub]; ok {
		delete(h.subs, sub)
		close(sub.ch)
	}
}

func (h *eventHub) count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.subs)
}

// publish sends an event to every subscriber the filter accepts. It never blocks: a
// subscriber whose buffer is full is dropped, which closes its stream.
func (h *eventHub) publish(name string, payload any, accept func(*subscriber) bool) {
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}
	message := eventMessage{name: name, data: data}
	h.mu.Lock()
	defer h.mu.Unlock()
	for sub := range h.subs {
		if !accept(sub) {
			continue
		}
		select {
		case sub.ch <- message:
		default:
			delete(h.subs, sub)
			close(sub.ch)
		}
	}
}

func (h *eventHub) toUser(owner, name string, payload any) {
	h.publish(name, payload, func(s *subscriber) bool { return s.role == roleUser && s.account == owner })
}

func (h *eventHub) toWorkers(name string, payload any) {
	h.publish(name, payload, func(s *subscriber) bool { return s.role == roleWorker })
}

// registerEvents publishes an event whenever a recording or an analysis is saved or
// deleted. The hooks run after the change is committed, so a client that reacts to an
// event by fetching sees it.
func registerEvents(app core.App) {
	analysisChanged := func(deleted bool) func(*core.RecordEvent) error {
		return func(e *core.RecordEvent) error {
			status := e.Record.GetString("status")
			if deleted {
				status = "deleted"
			}
			announceAnalysis(e.App, e.Record, status)
			return e.Next()
		}
	}
	app.OnRecordAfterCreateSuccess("analyses").BindFunc(analysisChanged(false))
	app.OnRecordAfterUpdateSuccess("analyses").BindFunc(analysisChanged(false))
	app.OnRecordAfterDeleteSuccess("analyses").BindFunc(analysisChanged(true))

	recordingChanged := func(change string) func(*core.RecordEvent) error {
		return func(e *core.RecordEvent) error {
			// Every uploaded chunk saves the recording; only a change to what a client
			// shows (its metadata, or being sealed) is worth an event.
			if original := e.Record.Original(); change == "updated" && original != nil &&
				original.GetString("meta") == e.Record.GetString("meta") &&
				original.GetBool("complete") == e.Record.GetBool("complete") {
				return e.Next()
			}
			events.toUser(e.Record.GetString("owner"), eventRecording, RecordingEvent{ID: e.Record.Id, Change: change})
			return e.Next()
		}
	}
	app.OnRecordAfterCreateSuccess("recordings").BindFunc(recordingChanged("created"))
	app.OnRecordAfterUpdateSuccess("recordings").BindFunc(recordingChanged("updated"))
	app.OnRecordAfterDeleteSuccess("recordings").BindFunc(recordingChanged("deleted"))
}

// announceAnalysis publishes an analysis event: to the recording's owner, and to workers
// when there is work for them. It is also called directly where the analysis is changed
// with SQL, which PocketBase's record hooks do not see (claiming).
func announceAnalysis(app core.App, record *core.Record, status string) {
	event := AnalysisEvent{ID: record.Id, Recording: record.GetString("recording"), Status: status}
	// A cascade from a deleted recording finds no owner; that recording's own event
	// covers it.
	if recording, err := app.FindRecordById("recordings", event.Recording); err == nil {
		events.toUser(recording.GetString("owner"), eventAnalysis, event)
	}
	if status == statusQueued {
		events.toWorkers(eventAnalysis, event)
	}
}

// StreamEvents serves the caller's events until the client goes away.
func StreamEvents(e *core.RequestEvent) error {
	sub := events.subscribe(roleOf(e), e.Auth.Id)
	defer events.unsubscribe(sub)

	// The stream outlives any write deadline the server sets for ordinary requests.
	_ = http.NewResponseController(e.Response).SetWriteDeadline(time.Time{})
	header := e.Response.Header()
	header.Set("Content-Type", "text/event-stream")
	header.Set("X-Accel-Buffering", "no") // keep nginx from buffering the stream
	e.Response.WriteHeader(http.StatusOK)
	if _, err := fmt.Fprint(e.Response, ": connected\nretry: 3000\n\n"); err != nil {
		return nil
	}
	e.Flush()

	ticker := time.NewTicker(eventKeepalive)
	defer ticker.Stop()
	for {
		select {
		case <-e.Request.Context().Done():
			return nil
		case message, ok := <-sub.ch:
			if !ok {
				return nil
			}
			if _, err := fmt.Fprintf(e.Response, "event: %s\ndata: %s\n\n", message.name, message.data); err != nil {
				return nil
			}
			e.Flush()
		case <-ticker.C:
			if _, err := fmt.Fprint(e.Response, ": keepalive\n\n"); err != nil {
				return nil
			}
			e.Flush()
		}
	}
}
