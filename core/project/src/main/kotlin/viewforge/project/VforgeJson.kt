@file:OptIn(ExperimentalSerializationApi::class)

package viewforge.project

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The single `Json` configuration for reading and writing `.vforge` files. Everything that
 * (de)serializes a project must go through this instance so the on-disk format is defined in one
 * place.
 *
 * Choices:
 * - `classDiscriminator = "kind"` — `PropValue` variants are tagged with `kind` (DATA_MODEL §6).
 * - `prettyPrint` + two-space indent — `.vforge` is committed to git; diffs must be readable
 *   (DATA_MODEL §1). Property declaration order gives stable key order.
 * - `encodeDefaults = false` — omit defaulted fields so a minimal document stays small and diffs
 *   don't churn on editor-only flags. `Project.schemaVersion` opts back in via `@EncodeDefault`.
 * - `ignoreUnknownKeys = true` — forward-tolerance: a newer app may add an optional field without a
 *   version bump (DATA_MODEL §10 additive policy), and older apps must still open the file. NOTE:
 *   unknown keys are dropped on re-save; full round-trip preservation of unknown fields is a
 *   deliberate deferral (DATA_MODEL §1 "where possible").
 * - `isLenient = false`, no array polymorphism, no structured map keys — strict parsing, and the
 *   sealed `PropValue` set can never instantiate an arbitrary class by name (PF-1).
 */
val VforgeJson: Json = Json {
    classDiscriminator = "kind"
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = false
    ignoreUnknownKeys = true
    isLenient = false
    allowStructuredMapKeys = false
    useArrayPolymorphism = false
}
