package com.example.textsearch.service;

import org.junit.Before;
import org.junit.Test;
import org.springframework.util.ResourceUtils;

import java.io.File;
import java.io.FileInputStream;
import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


public class SearchServiceTest {

    private SearchService searchService;

    @Before
    public void setUp() throws Exception {
        searchService = new SearchService();
    }

    @Test
    public void testSmall() throws Exception {
        File testFile = ResourceUtils.getFile("classpath:test1.txt");
        searchService.addSource("testFile", new FileInputStream(testFile));
        Map<String, Collection<Integer>> res = searchService.searchForString("g3, test1-again5");
        assertThat(res.get("testFile")).containsExactly(19);
        res = searchService.searchForString("test1, more2 testing3, test1-again5;end6");
        assertThat(res.get("testFile")).containsExactly(0);
        res = searchService.searchForString(", more2 testing3, test1-again5;end6");
        assertThat(res.get("testFile")).containsExactly(5);
        res = searchService.searchForString("test1");
        assertThat(res.get("testFile")).containsExactlyInAnyOrder(0, 23);
    }

    @Test
    public void testHashCollisions() throws Exception {
        File testFile = ResourceUtils.getFile("classpath:test3.txt");
        searchService.addSource("testFile", new FileInputStream(testFile));
        Map<String, Collection<Integer>> res = searchService.searchForString("t 1 FB val1");
        assertThat(res.get("testFile")).hasSize(1);
        res = searchService.searchForString("t 1 Ea val1");
        assertThat(res).isEmpty();
        res = searchService.searchForString("t 1 FB val2");
        assertThat(res).isEmpty();
    }

    @Test
    public void testSmall2() throws Exception {
        File testFile = ResourceUtils.getFile("classpath:test2.txt");
        searchService.addSource("testFile", new FileInputStream(testFile));
        Map<String, Collection<Integer>> res = searchService.searchForString("g3, test1-again5");
        assertThat(res.get("testFile")).containsExactly(20);
        res = searchService.searchForString("test1, more2 testing3, test1-again5;end6");
        assertThat(res.get("testFile")).containsExactly(1);
        res = searchService.searchForString(", more2 testing3, test1-again5;end6!");
        assertThat(res.get("testFile")).containsExactly(6);
        res = searchService.searchForString("test1");
        assertThat(res.get("testFile")).containsExactlyInAnyOrder(1, 24);
    }

    @Test
    public void testWaP() throws Exception {
        File testFile = ResourceUtils.getFile("classpath:war_and_peace.txt");
        String testName = "testFile";
        searchService.addSource(testName, new FileInputStream(testFile));
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
    public void testWaP3() throws Exception {
        File testFile = ResourceUtils.getFile("classpath:war_and_peace.txt");
        String testName = "testFile";
        searchService.addSource(testName, new FileInputStream(testFile));
        Map<String, Collection<Integer>> res = searchService.searchForString("lalala noanychance tomatch");
        assertThat(res).isEmpty();
    }

    @Test
    public void testWaP2() throws Exception {
        File testFile = ResourceUtils.getFile("classpath:war_and_peace.txt");
        String testName = "testFile";
        searchService.addSource(testName, new FileInputStream(testFile));
        Map<String, Collection<Integer>> res = searchService.searchForString("She was\r\n" +
                "going straight on through the conservatory, neither seeing nor hearing\r\n" +
                "anything");
        assertThat(res.get(testName)).hasSize(1);

    }

    @Test
    public void getUploadedFileNames() throws Exception {
        File testFile1 = ResourceUtils.getFile("classpath:test1.txt");
        searchService.addSource("testFile1", new FileInputStream(testFile1));
        File testFile2 = ResourceUtils.getFile("classpath:test2.txt");
        searchService.addSource("testFile2", new FileInputStream(testFile2));
        assertThat(searchService.getStoredSourceNames()).containsExactlyInAnyOrder("testFile1", "testFile2");
    }

}