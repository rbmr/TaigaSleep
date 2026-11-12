package api

import (
	"fmt"
	"math"
	"sort"
	"strings"
)

// validateParams checks an analysis request against the parameter schema the
// algorithm announced. The schemas algorithms publish are a small part of JSON
// Schema — an object with typed properties, optional bounds, enums and no
// additional properties — so this covers exactly that, in the same terms the
// worker will use. An algorithm that announces no schema accepts any object.
func validateParams(schema map[string]any, params map[string]any) error {
	if len(schema) == 0 {
		return nil
	}
	return validateParamValue(schema, params, "params")
}

func validateParamValue(schema map[string]any, value any, path string) error {
	if len(schema) == 0 {
		return nil
	}
	if types := paramSchemaTypes(schema["type"]); len(types) > 0 && !matchesAnyType(types, value) {
		return fmt.Errorf("%s must be %s", path, describeTypes(types))
	}
	if enum, ok := schema["enum"].([]any); ok && !matchesEnum(enum, value) {
		return fmt.Errorf("%s is not one of the announced values", path)
	}
	if number, ok := numberOf(value); ok {
		if minimum, ok := numberOf(schema["minimum"]); ok && number < minimum {
			return fmt.Errorf("%s must be at least %v", path, minimum)
		}
		if maximum, ok := numberOf(schema["maximum"]); ok && number > maximum {
			return fmt.Errorf("%s must be at most %v", path, maximum)
		}
	}
	if object, ok := value.(map[string]any); ok {
		if err := validateParamObject(schema, object, path); err != nil {
			return err
		}
	}
	if items, ok := value.([]any); ok {
		if itemSchema, ok := schema["items"].(map[string]any); ok {
			for index, item := range items {
				if err := validateParamValue(itemSchema, item, fmt.Sprintf("%s[%d]", path, index)); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

func validateParamObject(schema map[string]any, object map[string]any, path string) error {
	properties, _ := schema["properties"].(map[string]any)
	if required, ok := schema["required"].([]any); ok {
		for _, name := range required {
			key, _ := name.(string)
			if _, present := object[key]; !present {
				return fmt.Errorf("%s.%s is required", path, key)
			}
		}
	}
	keys := make([]string, 0, len(object))
	for key := range object {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		value := object[key]
		if property, ok := properties[key].(map[string]any); ok {
			if err := validateParamValue(property, value, path+"."+key); err != nil {
				return err
			}
			continue
		}
		switch additional := schema["additionalProperties"].(type) {
		case bool:
			if !additional {
				return fmt.Errorf("%s.%s is not an announced parameter", path, key)
			}
		case map[string]any:
			if err := validateParamValue(additional, value, path+"."+key); err != nil {
				return err
			}
		}
	}
	return nil
}

// schemaTypes reads the type of a schema: one name, or a list of alternatives with
// null for an optional value.
func paramSchemaTypes(raw any) []string {
	switch value := raw.(type) {
	case string:
		return []string{value}
	case []any:
		types := make([]string, 0, len(value))
		for _, item := range value {
			if name, ok := item.(string); ok {
				types = append(types, name)
			}
		}
		return types
	}
	return nil
}

func matchesAnyType(types []string, value any) bool {
	for _, name := range types {
		if matchesType(name, value) {
			return true
		}
	}
	return false
}

func matchesType(name string, value any) bool {
	switch name {
	case "null":
		return value == nil
	case "string":
		_, ok := value.(string)
		return ok
	case "boolean":
		_, ok := value.(bool)
		return ok
	case "number":
		_, ok := numberOf(value)
		return ok
	case "integer":
		number, ok := numberOf(value)
		return ok && number == math.Trunc(number)
	case "array":
		_, ok := value.([]any)
		return ok
	case "object":
		_, ok := value.(map[string]any)
		return ok
	}
	return true // an unknown type name does not reject a request
}

func numberOf(value any) (float64, bool) {
	switch number := value.(type) {
	case float64:
		return number, true
	case float32:
		return float64(number), true
	case int:
		return float64(number), true
	case int64:
		return float64(number), true
	case int32:
		return float64(number), true
	}
	return 0, false
}

func matchesEnum(values []any, value any) bool {
	for _, candidate := range values {
		if number, ok := numberOf(value); ok {
			if other, ok := numberOf(candidate); ok && number == other {
				return true
			}
			continue
		}
		if value == nil && candidate == nil {
			return true
		}
		if a, ok := value.(string); ok {
			if b, ok := candidate.(string); ok && a == b {
				return true
			}
		}
		if a, ok := value.(bool); ok {
			if b, ok := candidate.(bool); ok && a == b {
				return true
			}
		}
	}
	return false
}

func describeTypes(types []string) string {
	names := make([]string, 0, len(types))
	for _, name := range types {
		switch name {
		case "null":
			names = append(names, "null")
		case "integer", "object", "array":
			names = append(names, "an "+name)
		default:
			names = append(names, "a "+name)
		}
	}
	return strings.Join(names, " or ")
}
