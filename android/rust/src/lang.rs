//! Whether text read off the screen is worth translating. Text already in the language
//! translated into is not, and neither are the buttons and labels of the phone's apps, which
//! are in that language too but too short to tell it from: a few words that are common in it
//! decide those.

use whatlang::{Detector, Lang};

/// Fewer words than this are a label, a name or a count.
const MIN_WORDS: usize = 3;
/// From this many words on, text whose language is unclear is translated.
const SURE_WORDS: usize = 5;
/// This share of a text's words common in a language marks it as that language.
const COMMON_SHARE: f32 = 0.3;
/// Letters of the scripts written without spaces count as half a word each.
const CJK_PER_WORD: usize = 2;

/// Languages told apart: those of the targets and of what people meet most on their phones.
/// Fewer to choose from tells short text apart better.
const LIKELY: [Lang; 30] = [
    Lang::Eng,
    Lang::Tur,
    Lang::Aze,
    Lang::Spa,
    Lang::Fra,
    Lang::Deu,
    Lang::Ita,
    Lang::Por,
    Lang::Nld,
    Lang::Pol,
    Lang::Rus,
    Lang::Ukr,
    Lang::Ara,
    Lang::Pes,
    Lang::Hin,
    Lang::Ben,
    Lang::Cmn,
    Lang::Jpn,
    Lang::Kor,
    Lang::Ind,
    Lang::Vie,
    Lang::Tha,
    Lang::Ell,
    Lang::Swe,
    Lang::Ron,
    Lang::Ces,
    Lang::Hun,
    Lang::Heb,
    Lang::Urd,
    Lang::Bul,
];

/// Turkish as the recognizer reads it (it has no ç ğ ı ö ş) and folded to ASCII: words common
/// in posts and in apps' buttons, and none that are also common English, Spanish or French
/// words (de, en, her, once, son, su...).
const TR_WORDS: &[&str] = &[
    "ve",
    "ile",
    "bir",
    "bu",
    "icin",
    "cok",
    "daha",
    "gibi",
    "kadar",
    "veya",
    "degil",
    "evet",
    "hayir",
    "simdi",
    "bugun",
    "yarin",
    "neden",
    "nasil",
    "nerede",
    "hangi",
    "hic",
    "var",
    "yok",
    "olan",
    "oldu",
    "olarak",
    "olsun",
    "artik",
    "cunku",
    "belki",
    "sadece",
    "biraz",
    "bile",
    "kendi",
    "bunu",
    "bunun",
    "buna",
    "seni",
    "beni",
    "sana",
    "bana",
    "onlar",
    "onu",
    "onun",
    "benim",
    "senin",
    "bizim",
    "sizin",
    "siz",
    "biz",
    "sen",
    "iyi",
    "guzel",
    "harika",
    "merhaba",
    "selam",
    "abi",
    "hadi",
    "bak",
    "hep",
    "sey",
    "zaman",
    "insan",
    "insanlar",
    "hayat",
    "dunya",
    "ilk",
    "yap",
    "yapti",
    "et",
    "gor",
    "tumu",
    "tumunu",
    "fazla",
    "az",
    "mesaj",
    "ekle",
    "canli",
    "yayin",
    "kez",
    "kisi",
    "sayfa",
    "reklam",
    "ozel",
    "yeni",
    "saat",
    "dakika",
    "hafta",
    "saniye",
    "sa",
    "dk",
    "yil",
    "devami",
    "orijinal",
    "cevapla",
    "gizle",
    "kaldir",
    "sikayet",
    "engelle",
    "sponsorlu",
    "onerilen",
    "profil",
    "ayarlar",
    "anasayfa",
    "fotograf",
    "muzik",
    "kesfet",
    "herkese",
    "acik",
    "kapali",
    "bilgi",
    "kisa",
];

/// Beginnings of Turkish words that take endings (yorumlar, yorumun...): long enough not to begin
/// words of other languages.
const TR_STEMS: &[&str] = &[
    "yorum", "paylas", "begen", "takip", "hikaye", "arkadas", "kaydet", "bildirim", "cevir",
    "goster", "izle", "gonder", "tesekkur", "yanitla", "mesaj",
];

/// Common English words, but those that are common Turkish words too (at, her, on).
const EN_WORDS: &[&str] = &[
    "the",
    "and",
    "you",
    "your",
    "is",
    "are",
    "was",
    "were",
    "be",
    "been",
    "have",
    "has",
    "had",
    "do",
    "does",
    "did",
    "not",
    "this",
    "that",
    "these",
    "those",
    "what",
    "who",
    "how",
    "why",
    "when",
    "where",
    "all",
    "just",
    "so",
    "can",
    "will",
    "would",
    "should",
    "could",
    "love",
    "like",
    "get",
    "got",
    "go",
    "going",
    "gonna",
    "wanna",
    "know",
    "think",
    "see",
    "look",
    "make",
    "want",
    "need",
    "never",
    "ever",
    "always",
    "more",
    "most",
    "very",
    "really",
    "good",
    "best",
    "new",
    "time",
    "day",
    "people",
    "one",
    "up",
    "out",
    "about",
    "if",
    "then",
    "there",
    "here",
    "now",
    "today",
    "give",
    "take",
    "come",
    "life",
    "world",
    "man",
    "guys",
    "thank",
    "thanks",
    "please",
    "follow",
    "watch",
    "my",
    "me",
    "we",
    "they",
    "he",
    "she",
    "it",
    "its",
    "our",
    "their",
    "his",
    "him",
    "them",
    "to",
    "of",
    "in",
    "for",
    "with",
    "from",
    "by",
    "or",
    "but",
    "no",
    "yes",
    "too",
    "also",
    "only",
    "some",
    "every",
    "much",
    "many",
    "than",
    "after",
    "before",
    "because",
    "still",
    "even",
    "back",
    "way",
    "well",
    "right",
    "little",
    "big",
    "great",
    "first",
    "last",
    "other",
    "again",
    "off",
    "over",
    "into",
    "down",
    "made",
    "said",
    "say",
    "tell",
    "let",
    "try",
    "feel",
    "find",
    "keep",
    "put",
    "show",
    "call",
    "work",
    "home",
    "friend",
    "friends",
    "family",
    "free",
    "check",
    "link",
    "happy",
    "birthday",
    "cute",
    "beautiful",
    "amazing",
    "awesome",
    "lol",
    "omg",
    "wow",
    "congrats",
    "congratulations",
    "miss",
    "proud",
    "view",
    "everyone",
    "baby",
    "girl",
    "boy",
    "bro",
    "merry",
    "christmas",
    "year",
    "years",
    "morning",
    "night",
    "tonight",
    "tomorrow",
    "yesterday",
    "delicious",
];

/// Decides for text read off the screen whether it goes to be translated into one language.
pub struct Langs {
    detector: Detector,
    /// What is translated into; None when the detector does not know it.
    target: Option<Lang>,
}

impl Langs {
    /// For translating into `target`, a code of `TARGET_LANGS`.
    pub fn new(target: &str) -> Self {
        Self {
            detector: Detector::with_allowlist(LIKELY.to_vec()),
            target: lang_of(target),
        }
    }

    /// Whether `text` is to be translated: long enough to say something and, as far as can be
    /// told, not in the target language already.
    pub fn foreign(&self, text: &str) -> bool {
        let words = words(text);
        let cjk = text.chars().filter(|&c| is_cjk(c)).count();
        let n = words.len() + cjk / CJK_PER_WORD;
        if n < MIN_WORDS {
            return false;
        }
        let guess = self.detector.detect(text);
        if let Some(info) = &guess
            && info.is_reliable()
        {
            return !self.same(info.lang());
        }
        // Short text is often unclear: the words common in a language decide.
        let common = |list: &[&str], stems: &[&str]| {
            let hits = words
                .iter()
                .filter(|w| list.contains(&w.as_str()) || stems.iter().any(|s| w.starts_with(s)))
                .count();
            hits as f32 / words.len().max(1) as f32 >= COMMON_SHARE
        };
        let (tr, en) = (common(TR_WORDS, TR_STEMS), common(EN_WORDS, &[]));
        match self.target {
            Some(Lang::Tur) if tr => return false,
            Some(Lang::Eng) if en => return false,
            _ => {}
        }
        if (tr && self.target != Some(Lang::Tur)) || (en && self.target != Some(Lang::Eng)) {
            return true;
        }
        if guess.is_some_and(|info| self.same(info.lang())) {
            return false;
        }
        n >= SURE_WORDS
    }

    fn same(&self, lang: Lang) -> bool {
        match self.target {
            // Azerbaijani read without its letters is hard to tell from Turkish, and a Turkish
            // reader follows it.
            Some(Lang::Tur) => matches!(lang, Lang::Tur | Lang::Aze),
            Some(t) => lang == t,
            None => false,
        }
    }
}

/// The detector's language for a code of `TARGET_LANGS`.
fn lang_of(code: &str) -> Option<Lang> {
    Some(match code {
        "tr" => Lang::Tur,
        "en" => Lang::Eng,
        "es" => Lang::Spa,
        "fr" => Lang::Fra,
        "de" => Lang::Deu,
        "it" => Lang::Ita,
        "pt" => Lang::Por,
        "ru" => Lang::Rus,
        "ar" => Lang::Ara,
        "hi" => Lang::Hin,
        "bn" => Lang::Ben,
        "zh" | "zh-Hant" => Lang::Cmn,
        "ja" => Lang::Jpn,
        "ko" => Lang::Kor,
        "id" => Lang::Ind,
        "vi" => Lang::Vie,
        "th" => Lang::Tha,
        "nl" => Lang::Nld,
        "pl" => Lang::Pol,
        "uk" => Lang::Ukr,
        "el" => Lang::Ell,
        "sv" => Lang::Swe,
        "ro" => Lang::Ron,
        "cs" => Lang::Ces,
        "hu" => Lang::Hun,
        _ => return None,
    })
}

/// The words of at least two letters, lower case and folded to ASCII where they can be.
fn words(text: &str) -> Vec<String> {
    text.split(|c: char| !c.is_alphabetic())
        .filter(|w| w.chars().count() >= 2 && !w.chars().any(is_cjk))
        .map(fold)
        .collect()
}

/// Lower case, with the marks taken off the Latin letters the recognizer and Turkish use.
pub fn fold(word: &str) -> String {
    word.chars()
        .flat_map(char::to_lowercase)
        // İ lowercases to i and a combining dot.
        .filter(|&c| c != '\u{307}')
        .map(|c| match c {
            'ç' => 'c',
            'ğ' => 'g',
            'ı' => 'i',
            'ö' | 'ò' | 'ó' | 'ô' => 'o',
            'ş' => 's',
            'ü' | 'ù' | 'ú' | 'û' => 'u',
            'é' | 'è' | 'ê' | 'ë' | 'ē' => 'e',
            'á' | 'à' | 'â' | 'ä' | 'ā' => 'a',
            'í' | 'ì' | 'î' | 'ï' | 'ī' => 'i',
            c => c,
        })
        .collect()
}

/// Chinese, Japanese or Korean letters, written without spaces between words.
fn is_cjk(c: char) -> bool {
    matches!(c as u32,
        0x3040..=0x30ff | 0x3400..=0x4dbf | 0x4e00..=0x9fff | 0xac00..=0xd7af | 0xf900..=0xfaff)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Turkish as the recognizer reads it: no ç ğ ı ö ş.
    fn read(s: &str) -> String {
        s.chars()
            .map(|c| match c {
                'ç' => 'c',
                'Ç' => 'C',
                'ğ' => 'g',
                'ı' => 'i',
                'İ' => 'I',
                'ö' => 'o',
                'Ö' => 'O',
                'ş' => 's',
                'Ş' => 'S',
                c => c,
            })
            .collect()
    }

    #[test]
    fn into_turkish_only_foreign_sentences_go() {
        let l = Langs::new("tr");
        for t in [
            "Bugün hava çok güzel, herkese iyi haftalar dilerim",
            "Arkadaşlarla harika bir akşam geçirdik",
            "Beğen Yorum Yap Paylaş",
            "12 yorumun tümünü gör",
            "Daha fazla gör",
            "John Carter 3 sa",
            "Sen de mi Brutus",
            "Takip et",
            "John Carter 3 sa Herkese açık",
            "traveler_mike Takip et",
            "1,2 B 234 yorum 18 paylaşım",
            "Sponsorlu Daha fazla bilgi al",
        ] {
            assert!(!l.foreign(&read(t)), "{t}");
        }
        for t in [
            "Where did you put the keys? We have to leave before dawn.",
            "This is the best pizza I have ever had in my life",
            "Never gonna give up",
            "Press X to interact",
            "Liked by john_doe and 234 others",
            "Hola amigos, cómo están? Hoy vamos a cocinar algo delicioso",
            "Ich weiß nicht, was ich sagen soll, aber das war großartig",
            "Привет, как дела? Сегодня отличный день",
            "今天天气很好我们去公园",
            "Best sunset in Bali",
            "Happy birthday bro",
            "I can't believe we actually made it",
            "Look at this view",
        ] {
            assert!(l.foreign(t), "{t}");
        }
    }

    #[test]
    fn into_english_english_stays() {
        let l = Langs::new("en");
        assert!(!l.foreign("This is the best pizza I have ever had in my life"));
        assert!(!l.foreign("Never gonna give up"));
        assert!(l.foreign(&read("Bugün hava çok güzel, herkese iyi haftalar dilerim")));
        assert!(l.foreign(&read("Beğen Yorum Yap Paylaş")));
    }

    #[test]
    fn labels_names_and_counts_are_too_short() {
        let l = Langs::new("tr");
        for t in [
            "See more",
            "I love",
            "@traveler_mike",
            "1,2 B",
            "234 comments",
            "OK",
        ] {
            assert!(!l.foreign(t), "{t}");
        }
    }

    #[test]
    fn folding() {
        assert_eq!(fold("Çİğ Şöyle Ünlü"), "cig soyle unlu");
        assert_eq!(fold("Gönderi"), "gonderi");
    }
}
