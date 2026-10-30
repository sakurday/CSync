use crate::protocol::Clip;
use std::collections::{HashSet, VecDeque};

/// Bounded event deduplication. Equal text with a fresh event id is a new copy.
pub struct Coordinator {
    revision: u64,
    seen: HashSet<String>,
    order: VecDeque<String>,
}

impl Default for Coordinator {
    fn default() -> Self {
        Self {
            revision: 0,
            seen: HashSet::new(),
            order: VecDeque::new(),
        }
    }
}

impl Coordinator {
    pub fn commit(&mut self, clip: &Clip) -> Option<u64> {
        if !self.seen.insert(clip.id.clone()) {
            return None;
        }
        self.order.push_back(clip.id.clone());
        if self.order.len() > 1024 {
            self.seen.remove(&self.order.pop_front().unwrap());
        }
        self.revision += 1;
        Some(self.revision)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn retries_are_deduplicated_but_recopy_is_not() {
        let mut engine = Coordinator::default();
        let a = Clip::new(&"a".repeat(64), 1, "same text".into()).unwrap();
        assert_eq!(engine.commit(&a), Some(1));
        assert_eq!(engine.commit(&a), None);
        let b = Clip::new(&"a".repeat(64), 2, "same text".into()).unwrap();
        assert_eq!(engine.commit(&b), Some(2));
    }
}
