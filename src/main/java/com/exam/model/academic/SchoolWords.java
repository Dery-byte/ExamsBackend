package com.exam.model.academic;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Rewrites server text written in university words for the school modes, where a department is a
 * section (Primary School, Junior High School …) and a programme is a class (Primary 1, JHS 2 …).
 * A class is one year long, so phrases that pair a class with its level ("each class and year")
 * lose the level. The browser does the same for the page's own labels (tx() in utils/terms.ts).
 */
public final class SchoolWords {

    private SchoolWords() {}

    private record Rule(Pattern pattern, String to) {}

    private static Rule r(String regex, String to) { return new Rule(Pattern.compile(regex), to); }

    private static final List<Rule> RULES = List.of(
            r("\\bDepartments\\b", "Sections"), r("\\bDepartment\\b", "Section"),
            r("\\bdepartments\\b", "sections"), r("\\bdepartment\\b", "section"),
            r("\\bDEPARTMENTS\\b", "SECTIONS"), r("\\bDEPARTMENT\\b", "SECTION"),
            r("\\bProgrammes\\b", "Classes"), r("\\bProgramme\\b", "Class"),
            r("\\bprogrammes\\b", "classes"), r("\\bprogramme\\b", "class"),
            r("\\bPrograms\\b", "Classes"), r("\\bProgram\\b", "Class"),
            r("\\bprograms\\b", "classes"), r("\\bprogram\\b", "class"),
            // "class, year and term" → "class and term"; "class and year" / "class & current year" → "class"
            r("\\b([Cc]lass(?:es)?), (?:current )?years? and\\b", "$1 and"),
            r("\\b([Cc]lass(?:es)?),? (?:and|&|or) (?:current |its |their )?years?\\b", "$1"),
            r(" at (?:its|their) year\\b", ""));

    /** The text in the current mode's words (unchanged at a university). */
    public static String apply(String text) {
        if (text == null || text.isEmpty() || !SystemMode.current().isSchool()) return text;
        String out = text;
        for (Rule rule : RULES) out = rule.pattern().matcher(out).replaceAll(rule.to());
        return out;
    }
}
