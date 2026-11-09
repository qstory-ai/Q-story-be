package com.qstory.backend.extension.learning;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Vocabulary only. Never derives an "expected answer" from the child's target question. */
public final class VocabularyHints {
    private VocabularyHints() {}
    public static List<String> clean(List<String> keywords) {
        if (keywords == null) return List.of();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int chars = 0;
        for (String raw : keywords) {
            if (raw == null) continue;
            String word = Normalizer.normalize(raw, Normalizer.Form.NFC)
                    .replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
            // No punctuation/instruction-like text: a short name or noun phrase only.
            if (!word.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} _-]{0,23}")) continue;
            if (seen.contains(word)) continue;
            if (seen.size() >= 24 || chars + word.length() > 160) break;
            seen.add(word);
            chars += word.length();
        }
        return List.copyOf(new ArrayList<>(seen));
    }
}
