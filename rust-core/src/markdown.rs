use pulldown_cmark::{Event, Options, Parser, Tag, TagEnd};

#[derive(Clone, Debug)]
pub struct ParsedDoc {
    pub plain: String,
    pub spans: Vec<StyledSpan>,
}

#[derive(Clone, Debug)]
pub struct StyledSpan {
    pub start: u32,
    pub end: u32,
    pub style: SpanStyle,
}

#[derive(Clone, Debug)]
pub enum SpanStyle {
    Bold,
    Italic,
    Underline,
    Strike,
    Code,
    Heading { level: u8 },
    BulletItem,
    OrderedItem { number: u32 },
    Link { url: String },
    BlockQuote,
}

struct OpenStyle {
    style: SpanStyle,
    start: u32,
}

pub fn parse_markdown(src: String) -> ParsedDoc {
    let mut opts = Options::empty();
    opts.insert(Options::ENABLE_STRIKETHROUGH);
    opts.insert(Options::ENABLE_TASKLISTS);

    let parser = Parser::new_ext(&src, opts);
    let mut plain = String::with_capacity(src.len());
    let mut spans: Vec<StyledSpan> = Vec::new();
    let mut stack: Vec<OpenStyle> = Vec::new();
    let mut ordered_counter: Vec<u32> = Vec::new();
    let mut in_ordered: Vec<bool> = Vec::new();

    for ev in parser {
        match ev {
            Event::Start(tag) => match tag {
                Tag::Strong => stack.push(OpenStyle { style: SpanStyle::Bold, start: plain.len() as u32 }),
                Tag::Emphasis => stack.push(OpenStyle { style: SpanStyle::Italic, start: plain.len() as u32 }),
                Tag::Strikethrough => stack.push(OpenStyle { style: SpanStyle::Strike, start: plain.len() as u32 }),
                Tag::Heading { level, .. } => stack.push(OpenStyle {
                    style: SpanStyle::Heading { level: level as u8 },
                    start: plain.len() as u32,
                }),
                Tag::BlockQuote => stack.push(OpenStyle { style: SpanStyle::BlockQuote, start: plain.len() as u32 }),
                Tag::CodeBlock(_) => stack.push(OpenStyle { style: SpanStyle::Code, start: plain.len() as u32 }),
                Tag::Link { dest_url, .. } => stack.push(OpenStyle {
                    style: SpanStyle::Link { url: dest_url.to_string() },
                    start: plain.len() as u32,
                }),
                Tag::List(first) => {
                    in_ordered.push(first.is_some());
                    ordered_counter.push(first.unwrap_or(1) as u32);
                }
                Tag::Item => {
                    let is_ordered = *in_ordered.last().unwrap_or(&false);
                    if is_ordered {
                        let n = *ordered_counter.last().unwrap_or(&1);
                        stack.push(OpenStyle {
                            style: SpanStyle::OrderedItem { number: n },
                            start: plain.len() as u32,
                        });
                    } else {
                        stack.push(OpenStyle { style: SpanStyle::BulletItem, start: plain.len() as u32 });
                    }
                }
                _ => {}
            },
            Event::End(end) => match end {
                TagEnd::Strong | TagEnd::Emphasis | TagEnd::Strikethrough | TagEnd::Heading(_)
                | TagEnd::BlockQuote | TagEnd::CodeBlock | TagEnd::Link | TagEnd::Item => {
                    if let Some(open) = stack.pop() {
                        let end_pos = plain.len() as u32;
                        if end_pos > open.start {
                            spans.push(StyledSpan { start: open.start, end: end_pos, style: open.style });
                        }
                    }
                    if matches!(end, TagEnd::Heading(_) | TagEnd::BlockQuote | TagEnd::CodeBlock | TagEnd::Item) {
                        plain.push('\n');
                    }
                    if matches!(end, TagEnd::Item) {
                        if let Some(c) = ordered_counter.last_mut() { *c += 1; }
                    }
                }
                TagEnd::List(_) => {
                    in_ordered.pop();
                    ordered_counter.pop();
                    plain.push('\n');
                }
                TagEnd::Paragraph => plain.push('\n'),
                _ => {}
            },
            Event::Text(t) => plain.push_str(&t),
            Event::Code(t) => {
                let start = plain.len() as u32;
                plain.push_str(&t);
                spans.push(StyledSpan { start, end: plain.len() as u32, style: SpanStyle::Code });
            }
            Event::SoftBreak | Event::HardBreak => plain.push('\n'),
            Event::Rule => plain.push_str("\n———\n"),
            _ => {}
        }
    }

    ParsedDoc { plain, spans }
}
