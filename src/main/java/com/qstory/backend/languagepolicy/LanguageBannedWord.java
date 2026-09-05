package com.qstory.backend.languagepolicy;

/** language-rules.yaml의 bannedWords[] 항목 - 예: {"노파", "늙은 할머니"}. */
public record LanguageBannedWord(String word, String replacement) {}
