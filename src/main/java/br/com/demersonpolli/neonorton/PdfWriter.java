package br.com.demersonpolli.neonorton;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * F7's printer target: a minimal, dependency-free PDF writer. Content is plain monospace text
 * in Courier — one of the 14 standard PDF fonts, so no font embedding is needed — with a fixed
 * page margin, one page per `linesPerPage` lines, and nothing else (no syntax highlighting,
 * headers, or footers), matching the spec's minimal printer feature set.
 *
 * Only Latin-1 (code points 0x20-0xFF) is representable: a base-14 font plus WinAnsiEncoding
 * has no way to show characters outside that range without embedding a Unicode-capable font,
 * which is out of scope here. Anything outside that range is replaced with '?'.
 *
 * The whole file is built as one String restricted to those code points, then written out as
 * ISO-8859-1 bytes — that keeps "character offset into the string" and "byte offset into the
 * file" identical throughout, which is what makes the xref table's byte offsets trivial to
 * compute correctly without a separate byte-counting pass.
 */
public class PdfWriter {

    private static final double PAGE_WIDTH  = 612;  // US Letter, in points
    private static final double PAGE_HEIGHT = 792;
    private static final double MARGIN      = 36;   // fixed page margin around the text block
    private static final double FONT_SIZE   = 10;
    private static final double LEADING     = 12;   // line spacing

    /** Write `lines` (already including any left-margin padding) as a paginated PDF at `path`.
     *  `linesPerPage <= 0` means fit as many lines as the page allows, single page onward. */
    public static void write(Path path, List<String> lines, int linesPerPage) throws IOException {
        int perPage = linesPerPage > 0 ? linesPerPage
                : Math.max(1, (int) ((PAGE_HEIGHT - 2 * MARGIN) / LEADING));
        List<List<String>> pages = paginate(lines, perPage);
        if (pages.isEmpty()) pages.add(new ArrayList<>());

        StringBuilder pdf = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0); // index 0 is unused (object numbers start at 1)

        pdf.append("%PDF-1.4\n");

        int catalogObj = 1, pagesObj = 2, fontObj = 3;
        int firstPageObj = 4; // page i -> firstPageObj + 2*i ; its content stream -> +1

        beginObj(pdf, offsets, catalogObj);
        pdf.append("<< /Type /Catalog /Pages ").append(pagesObj).append(" 0 R >>\nendobj\n");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) kids.append(' ');
            kids.append(firstPageObj + 2 * i).append(" 0 R");
        }
        beginObj(pdf, offsets, pagesObj);
        pdf.append("<< /Type /Pages /Kids [").append(kids).append("] /Count ")
           .append(pages.size()).append(" >>\nendobj\n");

        beginObj(pdf, offsets, fontObj);
        pdf.append("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>\nendobj\n");

        for (int i = 0; i < pages.size(); i++) {
            int pageObj = firstPageObj + 2 * i;
            int contentObj = pageObj + 1;

            beginObj(pdf, offsets, pageObj);
            pdf.append("<< /Type /Page /Parent ").append(pagesObj).append(" 0 R ")
               .append("/MediaBox [0 0 ").append((int) PAGE_WIDTH).append(' ').append((int) PAGE_HEIGHT).append("] ")
               .append("/Resources << /Font << /F1 ").append(fontObj).append(" 0 R >> >> ")
               .append("/Contents ").append(contentObj).append(" 0 R >>\nendobj\n");

            String content = buildContentStream(pages.get(i));
            beginObj(pdf, offsets, contentObj);
            pdf.append("<< /Length ").append(content.length()).append(" >>\nstream\n")
               .append(content).append("\nendstream\nendobj\n");
        }

        int xrefOffset = pdf.length();
        int objCount = offsets.size();
        pdf.append("xref\n0 ").append(objCount).append('\n');
        pdf.append("0000000000 65535 f \n");
        for (int i = 1; i < objCount; i++) {
            pdf.append(String.format("%010d 00000 n \n", offsets.get(i)));
        }
        pdf.append("trailer\n<< /Size ").append(objCount).append(" /Root ").append(catalogObj).append(" 0 R >>\n");
        pdf.append("startxref\n").append(xrefOffset).append("\n%%EOF");

        Files.write(path, pdf.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Records `pdf`'s current length as object `objNum`'s byte offset and opens "N 0 obj". */
    private static void beginObj(StringBuilder pdf, List<Integer> offsets, int objNum) {
        while (offsets.size() <= objNum) offsets.add(0);
        offsets.set(objNum, pdf.length());
        pdf.append(objNum).append(" 0 obj\n");
    }

    private static List<List<String>> paginate(List<String> lines, int perPage) {
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += perPage) {
            pages.add(lines.subList(i, Math.min(i + perPage, lines.size())));
        }
        return pages;
    }

    private static String buildContentStream(List<String> pageLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("BT\n/F1 ").append(fmt(FONT_SIZE)).append(" Tf\n");
        sb.append(fmt(LEADING)).append(" TL\n");
        sb.append(fmt(MARGIN)).append(' ').append(fmt(PAGE_HEIGHT - MARGIN - FONT_SIZE)).append(" Td\n");
        boolean first = true;
        for (String line : pageLines) {
            if (!first) sb.append("T*\n");
            first = false;
            sb.append('(').append(escapePdfText(line)).append(") Tj\n");
        }
        sb.append("ET");
        return sb.toString();
    }

    /** Formats a double the way PDF wants numbers: no unnecessary decimal point/zeros. */
    private static String fmt(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    /** Escapes '\', '(', ')' for a PDF literal string, maps control chars to a space, and
     *  replaces anything outside Latin-1 with '?' (see the class doc comment). */
    private static String escapePdfText(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '(' || c == ')') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(' ');
            } else if (c > 0xFF) {
                out.append('?');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
