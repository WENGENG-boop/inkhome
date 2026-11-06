package ink.home;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** A reflowable book split into chapters of HTML. */
interface Content {
    final class TocEntry {
        final String title;
        final int chapter;

        TocEntry(String title, int chapter) {
            this.title = title;
            this.chapter = chapter;
        }
    }

    int chapters();

    String html(int chapter) throws IOException;

    /** Location the chapter's relative links resolve against. */
    File base(int chapter);

    List<TocEntry> toc();
}
