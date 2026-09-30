package com.qstory.backend.choicecopy.service;

import com.qstory.backend.choicecopy.ChoiceCopyVariant;
import com.qstory.backend.provider.openrouter.RouteOption;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** choice-copy.mjs를 Java로 이식한 것. */
@Service
public class ChoiceCopyService {

    private final ChoiceCopyRegistry copyBank;

    public ChoiceCopyService(ChoiceCopyRegistry copyBank) {
        this.copyBank = copyBank;
    }

    /** choice-copy.mjs의 stableIndex()와 동일한, UTF-8 코드 포인트에 대한 31배수 문자열 해시. */
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
}
