package com.qstory.backend.choicecopy;

import com.qstory.backend.provider.openrouter.RouteOption;
import com.qstory.backend.story.ActionFamily;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Java port of choice-copy.mjs. */
@Service
public class ChoiceCopyService {

    private final ChoiceCopyRegistry copyBank;

    public ChoiceCopyService(ChoiceCopyRegistry copyBank) {
        this.copyBank = copyBank;
    }

    public record CopyExample(String actionFamilyId, List<Example> examples) {
        public record Example(String label, String meaning) {}
    }

    /** Same 31-multiplier string hash as stableIndex() in choice-copy.mjs, over UTF-8 code points. */
    private static int stableIndex(String seed, int length) {
        long hash = 0;
        for (int codePoint : seed.codePoints().toArray()) {
            hash = (hash * 31 + codePoint) & 0xFFFFFFFFL;
        }
        return (int) (hash % length);
    }

    public List<RouteOption> authoredChoiceOptions(List<RouteOption> options, String transcript, int questionRound) {
        List<RouteOption> result = new ArrayList<>();
        for (int index = 0; index < options.size(); index++) {
            RouteOption option = options.get(index);
            List<ChoiceCopyVariant> variants = copyBank.variantsFor(option.actionFamilyId());
            if (variants.isEmpty()) {
                result.add(option);
                continue;
            }
            String seed = transcript + ":" + questionRound + ":" + option.actionFamilyId() + ":" + index;
            ChoiceCopyVariant variant = variants.get(stableIndex(seed, variants.size()));
            result.add(option.withCopy(variant.label(), variant.meaning()));
        }
        return result;
    }

    public List<CopyExample> choiceCopyBankForFamilies(List<ActionFamily> families) {
        return families.stream()
                .map(family -> new CopyExample(
                        family.id(),
                        copyBank.variantsFor(family.id()).stream()
                                .map(variant -> new CopyExample.Example(variant.label(), variant.meaning()))
                                .toList()))
                .toList();
    }
}
