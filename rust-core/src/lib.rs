mod markdown;
mod search;
mod meta;
mod format;
mod index;
mod fuzzy;

pub use markdown::{parse_markdown, ParsedDoc, StyledSpan, SpanStyle};
pub use format::{apply_format, FormatKind};
pub use meta::{extract_meta, NoteMeta};
pub use search::search_notes;
pub use index::{SearchIndex, IndexError};
pub use fuzzy::{fuzzy_search, FuzzyResult};

uniffi::include_scaffolding!("blacknote");
