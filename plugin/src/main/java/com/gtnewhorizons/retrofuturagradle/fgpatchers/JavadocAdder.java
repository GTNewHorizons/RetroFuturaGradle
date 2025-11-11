package com.gtnewhorizons.retrofuturagradle.fgpatchers;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.text.TextStringBuilder;

import com.google.common.base.Splitter;

public final class JavadocAdder {

    private JavadocAdder() {
        /* no constructing */
    }

    /**
     * Converts a raw javadoc string into a nicely formatted, indented, and wrapped string.
     *
     * @param indent   the indent to be inserted before every line.
     * @param javadoc  The javadoc string to be processed
     * @param isMethod If this javadoc is for a method or a field
     * @return A fully formatted javadoc comment string complete with comment characters and newlines.
     */
    public static String buildJavadoc(String indent, String javadoc, boolean isMethod) {
        StringBuilder builder = new StringBuilder();

        // split and wrap.
        List<String> list = new ArrayList<>();
        for (String line : Splitter.on("\\n").splitToList(javadoc)) {
            list.addAll(wrapText(line, 120 - (indent.length() + 3)));
        }

        if (list.size() > 1 || isMethod) {
            builder.append(indent);
            builder.append("/**");
            builder.append(System.lineSeparator());

            for (String line : list) {
                builder.append(indent);
                builder.append(" * ");
                builder.append(line);
                builder.append(System.lineSeparator());
            }

            builder.append(indent);
            builder.append(" */");
            // builder.append(Constants.NEWLINE);

        }
        // one line
        else {
            builder.append(indent);
            builder.append("/** ");
            builder.append(javadoc);
            builder.append(" */");
            // builder.append(Constants.NEWLINE);
        }

        return builder.toString();
    }

    private static List<String> wrapText(String text, int len) {
        // return empty array for null text
        if (text == null) {
            return List.of();
        }

        // return text if len is zero or less
        if (len <= 0) {
            return List.of(text);
        }

        // return text if less than length
        if (text.length() <= len) {
            return List.of(text);
        }

        List<String> lines = new ArrayList<>();
        TextStringBuilder line = new TextStringBuilder();
        TextStringBuilder word = new TextStringBuilder();
        int tempNum;

        // each char in array
        for (char c : text.toCharArray()) {
            // its a wordBreaking character.
            if (c == ' ' || c == ',' || c == '-') {
                // add the character to the word
                word.append(c);

                // its a space. set TempNum to 1, otherwise leave it as a wrappable char
                tempNum = Character.isWhitespace(c) ? 1 : 0;

                // subtract tempNum from the length of the word
                if ((line.length() + word.length() - tempNum) > len) {
                    lines.add(line.trim().toString());
                    line.clear();
                }

                // new word, add it to the next line and clear the word
                line.append(word);
                word.clear();

            }
            // not a linebreak char
            else {
                // add it to the word and move on
                word.append(c);
            }
        }

        // handle any extra chars in current word
        if (!word.isEmpty()) {
            if ((line.length() + word.length()) > len) {
                lines.add(line.trim().toString());
                line.clear();
            }
            line.append(word);
        }

        // handle extra line
        if (!line.isEmpty()) {
            lines.add(line.trim().toString());
        }

        return lines;
    }
}
