#[derive(Clone, Debug)]
pub enum FormatKind {
    Bold,
    Italic,
    Underline,
    Strike,
    Code,
    BulletList,
    OrderedList,
}

pub fn apply_format(src: String, sel_start: u32, sel_end: u32, kind: FormatKind, on: bool) -> String {
    let (start, end) = clamp_range(&src, sel_start as usize, sel_end as usize);
    let before = &src[..start];
    let middle = &src[start..end];
    let after = &src[end..];

    match kind {
        FormatKind::Bold      => wrap_inline(before, middle, after, "**", on),
        FormatKind::Italic    => wrap_inline(before, middle, after, "_", on),
        FormatKind::Strike    => wrap_inline(before, middle, after, "~~", on),
        FormatKind::Code      => wrap_inline(before, middle, after, "`", on),
        FormatKind::Underline => wrap_html(before, middle, after, "u", on),
        FormatKind::BulletList  => toggle_list(before, middle, after, ListKind::Bullet),
        FormatKind::OrderedList => toggle_list(before, middle, after, ListKind::Ordered),
    }
}

fn clamp_range(s: &str, mut a: usize, mut b: usize) -> (usize, usize) {
    if a > b { std::mem::swap(&mut a, &mut b); }
    let len = s.len();
    a = a.min(len);
    b = b.min(len);
    while !s.is_char_boundary(a) && a > 0 { a -= 1; }
    while !s.is_char_boundary(b) && b < len { b += 1; }
    (a, b)
}

fn wrap_inline(before: &str, middle: &str, after: &str, marker: &str, on: bool) -> String {
    if !on {
        let m = middle.trim_start_matches(marker).trim_end_matches(marker);
        return format!("{}{}{}", before, m, after);
    }
    if middle.is_empty() {
        format!("{}{}{}{}", before, marker, marker, after)
    } else {
        format!("{}{}{}{}{}", before, marker, middle, marker, after)
    }
}

fn wrap_html(before: &str, middle: &str, after: &str, tag: &str, on: bool) -> String {
    if !on {
        let open = format!("<{}>", tag);
        let close = format!("</{}>", tag);
        let m = middle.trim_start_matches(&*open).trim_end_matches(&*close);
        return format!("{}{}{}", before, m, after);
    }
    format!("{}<{}>{}</{}>{}", before, tag, middle, tag, after)
}

enum ListKind { Bullet, Ordered }

fn toggle_list(before: &str, middle: &str, after: &str, kind: ListKind) -> String {
    let bullet_pfx = "- ";
    let mut out = String::with_capacity(before.len() + middle.len() + after.len() + 8);
    out.push_str(before);
    if !before.ends_with('\n') && !before.is_empty() {
        out.push('\n');
    }
    if middle.is_empty() {
        match kind {
            ListKind::Bullet => out.push_str("- "),
            ListKind::Ordered => out.push_str("1. "),
        }
        out.push_str(after);
        return out;
    }
    let mut counter: u32 = 1;
    for line in middle.lines() {
        match kind {
            ListKind::Bullet => {
                if let Some(rest) = line.strip_prefix(bullet_pfx) { out.push_str(rest); }
                else { out.push_str(bullet_pfx); out.push_str(line); }
            }
            ListKind::Ordered => {
                let mut stripped = false;
                if let Some(idx) = line.find(". ") {
                    if line[..idx].chars().all(|c| c.is_ascii_digit()) && !line[..idx].is_empty() {
                        out.push_str(&line[idx + 2..]);
                        stripped = true;
                    }
                }
                if !stripped {
                    out.push_str(&format!("{}. {}", counter, line));
                    counter += 1;
                }
            }
        }
        out.push('\n');
    }
    out.push_str(after);
    out
}
