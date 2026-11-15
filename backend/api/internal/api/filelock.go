package api

import "sync"

// recordingFileLocks serialises the changes to one recording's stored files. A chunk
// reads from the file where it has to start, so two appends that ran together would
// both see the same size and try to write at the same place; the lock makes the
// second see the first one's committed size. Waiters on different recordings never
// wait for each other, and a lock is dropped when its last holder is done, so the map
// does not grow with every recording the server has served.
var recordingFileLocks = newFileLockSet()

type fileLockSet struct {
	mu      sync.Mutex
	entries map[string]*fileLock
}

type fileLock struct {
	mu      sync.Mutex
	holders int
}

func newFileLockSet() *fileLockSet {
	return &fileLockSet{entries: map[string]*fileLock{}}
}

// lock blocks until the recording's files are free and returns the function that
// releases them.
func (l *fileLockSet) lock(id string) func() {
	l.mu.Lock()
	entry := l.entries[id]
	if entry == nil {
		entry = &fileLock{}
		l.entries[id] = entry
	}
	entry.holders++
	l.mu.Unlock()

	entry.mu.Lock()
	return func() {
		entry.mu.Unlock()
		l.mu.Lock()
		entry.holders--
		if entry.holders == 0 {
			delete(l.entries, id)
		}
		l.mu.Unlock()
	}
}
