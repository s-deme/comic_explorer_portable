import jp.yaman.comicexplorer.ComicFile;

// Run from the project root:
// javac -encoding UTF-8 -d build/comic-file-test app/src/main/java/jp/yaman/comicexplorer/ComicFile.java tests/ComicFileTest.java
// java -ea -cp build/comic-file-test ComicFileTest
public final class ComicFileTest {
    public static void main(String[] args) {
        String[][] orderedPairs = {
                {"page2.jpg", "page10.jpg"},
                {"page2.jpg", "page02.jpg"},
                {"page7a.jpg", "page7B.jpg"},
                {"page9223372036854775808.jpg", "page9223372036854775809.jpg"},
                {"page100000000000000000000.jpg", "page9.jpg"},
                {"page9223372036854775808A.jpg", "page9223372036854775808b.jpg"}
        };
        for (String[] pair : orderedPairs) {
            assert ComicFile.NATURAL_NAME_ORDER.compare(pair[0], pair[1]) < 0 : pair[0];
            assert ComicFile.NATURAL_NAME_ORDER.compare(pair[1], pair[0]) > 0 : pair[1];
        }
        assert ComicFile.NATURAL_NAME_ORDER.compare("PAGE2.jpg", "page2.jpg") == 0;
        assert ComicFile.NATURAL_NAME_ORDER.compare("page9223372036854775808.jpg",
                "page9223372036854775808.jpg") == 0;
        System.out.println("ComicFile natural ordering: 14 checks passed.");
    }
}
