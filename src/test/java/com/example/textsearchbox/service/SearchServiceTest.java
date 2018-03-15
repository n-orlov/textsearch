package com.example.textsearchbox.service;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StopWatch;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(value = Parameterized.class)
public class SearchServiceTest {

    int loadFileToMemorySizeLimit;
    int buildIndexSizeLimit;

    private SearchService searchService;

    public SearchServiceTest(int loadFileToMemorySizeLimit, int buildIndexSizeLimit) {
        this.loadFileToMemorySizeLimit = loadFileToMemorySizeLimit;
        this.buildIndexSizeLimit = buildIndexSizeLimit;
    }

    @Parameterized.Parameters
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][] {{0, 0}, {0, 1000}, {1000, 0}, {1000, 1000},
                {10000000, 0}, {0, 10000000}, {10000000, 10000000}});
    }

    @Before
    public void setUp() throws Exception {
        initService(loadFileToMemorySizeLimit, buildIndexSizeLimit);
    }

    private void initService(int loadFileToMemorySizeLimit, int buildIndexSizeLimit) {
        searchService = new SearchService(loadFileToMemorySizeLimit, buildIndexSizeLimit);
    }

    @Test
    public void testSmall() throws Exception {
        String testFile = "testFile";
        searchService.addSource(testFile, ResourceUtils.getFile("classpath:texts/test1.txt"));
        Map<String, Collection<Integer>> res = searchService.searchForString("g3, test1-again5");
        assertThat(res.get(testFile)).containsExactly(19);
        res = searchService.searchForString("test1, more2 testing3, test1-again5;end6");
        assertThat(res.get(testFile)).containsExactly(0);
        res = searchService.searchForString(", more2 testing3, test1-again5;end6");
        assertThat(res.get(testFile)).containsExactly(5);
        res = searchService.searchForString("test1");
        assertThat(res.get(testFile)).containsExactlyInAnyOrder(0, 23);
        res = searchService.searchForString("test1-again5;end6");
        assertThat(res.get(testFile)).containsExactly(23);
        res = searchService.searchForString("end6");
        assertThat(res.get(testFile)).containsExactly(36);
    }

    @Test
    public void testSmall2() throws Exception {
        String testFile = "testFile";
        searchService.addSource(testFile, ResourceUtils.getFile("classpath:texts/test2.txt"));
        Map<String, Collection<Integer>> res = searchService.searchForString("g3, test1-again5");
        assertThat(res.get(testFile)).containsExactly(20);
        res = searchService.searchForString("test1, more2 testing3, test1-again5;end6");
        assertThat(res.get(testFile)).containsExactly(1);
        res = searchService.searchForString(", more2 testing3, test1-again5;end6!");
        assertThat(res.get(testFile)).containsExactly(6);
        res = searchService.searchForString("test1");
        assertThat(res.get(testFile)).containsExactlyInAnyOrder(1, 24);
    }

    @Test
    public void testHashCollisions() throws Exception {
        searchService.addSource("testFile", ResourceUtils.getFile("classpath:texts/test3.txt"));
        Map<String, Collection<Integer>> res = searchService.searchForString("t 1 FB val1");
        assertThat(res.get("testFile")).hasSize(1);
        res = searchService.searchForString("t 1 Ea val1");
        assertThat(res).isEmpty();
        res = searchService.searchForString("t 1 FB val2");
        assertThat(res).isEmpty();
    }

    @Test
    public void testWaP() throws Exception {
        String testName = "testFile";
        searchService.addSource(testName, ResourceUtils.getFile("classpath:texts/war_and_peace.txt"));
        String searchFor1 = "Where are they off to now";
        Map<String, Collection<Integer>> res = searchService.searchForString(searchFor1);
        assertThat(res.get(testName)).containsExactly(1057391);
        String actualPart = searchService.getFilePart(testName, res.get(testName).iterator().next() - 100, searchFor1.length() + 200);
        System.out.println(actualPart.substring(0, 99) + "#" + actualPart.substring(100, 100 + searchFor1.length()) + "#" + actualPart.substring(100 + searchFor1.length()));
        res = searchService.searchForString("It is mutiny—seizing the transport");
        assertThat(res.get(testName)).containsExactly(1059013);
        res = searchService.searchForString("sincere desire not to");
        assertThat(res).isEmpty();
        res = searchService.searchForString("Rostóv");
        assertThat(res.get(testName)).hasSize(965);
    }

    @Test
    public void testWaP2() throws Exception {
        File testFile = ResourceUtils.getFile("classpath:texts/war_and_peace.txt");
        String testName = "testFile";
        searchService.addSource(testName, testFile);
        Map<String, Collection<Integer>> res = searchService.searchForString("She was\r\n" +
                "going straight on through the conservatory, neither seeing nor hearing\r\n" +
                "anything");
        assertThat(res.get(testName)).hasSize(1);
    }

    @Test
    public void testWaP3() throws Exception {
        searchService.addSource("testFile", ResourceUtils.getFile("classpath:texts/war_and_peace.txt"));
        Map<String, Collection<Integer>> res = searchService.searchForString("lalala noanychance tomatch");
        assertThat(res).isEmpty();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldNotAddEmpty() throws Exception {
        searchService.addSource("testFile", ResourceUtils.getFile("classpath:texts/empty.txt"));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldNotAddTwice() throws Exception {
        searchService.addSource("testFile", ResourceUtils.getFile("classpath:texts/test1.txt"));
        searchService.addSource("testFile", ResourceUtils.getFile("classpath:texts/test1.txt"));
    }
    @Test
    public void shouldGetSource() throws Exception {
        searchService.addSource("testFile", ResourceUtils.getFile("classpath:texts/test1.txt"));
        try (InputStream res = searchService.getSource("testFile")) {
            try (Scanner scanner = new Scanner(res)) {
                assertThat(scanner.useDelimiter("\\A").next()).isEqualTo("test1, more2 testing3, test1-again5;end6");
            }
        }
    }

    @Test
    public void shouldGetFilePart() throws Exception {
        searchService.addSource("testFile", ResourceUtils.getFile("classpath:texts/test1.txt"));
        String res = searchService.getFilePart("testFile", 7, 5);
        assertThat(res).isEqualTo("more2");
    }

    @Test
    public void shouldGetFilePart2() throws Exception {
        searchService.addSource("testFile", ResourceUtils.getFile("classpath:texts/test1.txt"));
        String res = searchService.getFilePart("testFile1", 7, 5);
        assertThat(res).isNull();
    }

/*    @Test
    public void shouldGetSource2() throws Exception {
        searchService.addSource("testFile", ResourceUtils.getFile("classpath:texts/test1.txt"));
        String res = searchService.getSource("testFile1");
        assertThat(res).isNull();
    }*/

    @Test
    public void shouldNotSearchForShorterThanThreeChars() throws Exception {
        searchService.addSource("testFile", ResourceUtils.getFile("classpath:texts/war_and_peace.txt"));
        Map<String, Collection<Integer>> res = searchService.searchForString("th");
        assertThat(res).isEmpty();
    }

    @Test
    public void getUploadedFileNames() throws Exception {
        File testFile1 = ResourceUtils.getFile("classpath:texts/test1.txt");
        searchService.addSource("testFile1", testFile1);
        File testFile2 = ResourceUtils.getFile("classpath:texts/test2.txt");
        searchService.addSource("testFile2", testFile2);
        assertThat(searchService.getStoredSourceNames()).containsExactlyInAnyOrder("testFile1", "testFile2");
    }

}