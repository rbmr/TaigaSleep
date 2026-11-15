// Package results holds the one size limit of an analysis result.
//
// The same limit is the request body limit of the complete route and the maximum
// the stored result may have, so a result the API accepts is always one the
// database can hold.
package results

// MaxBytes is the largest analysis result the API accepts and stores.
const MaxBytes = 4 * 1024 * 1024
