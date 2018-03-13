package com.example.textsearch;

import com.example.textsearch.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StopWatch;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
@SpringBootTest
@Slf4j
public class PerformanceTest {

	private ExecutorService executorService = Executors.newFixedThreadPool(300);

	@Autowired
	private SearchService searchService;

	@Test
	public void runLoadTest() throws Exception {
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		searchService.addSource("war_and_peace.txt", new FileInputStream(ResourceUtils.getFile("classpath:war_and_peace.txt")));
		searchService.addSource("pride_and_prejudice.txt", new FileInputStream(ResourceUtils.getFile("classpath:pride_and_prejudice.txt")));
		searchService.addSource("Iliad.txt", new FileInputStream(ResourceUtils.getFile("classpath:Iliad.txt")));
		List<String> searches = Arrays.asList(
				"Where are they off to now",
				"no further; for on entering that place",
				"Denísov",
				"instantly",
				"obviously false match",
				"obviouslyfalsematch",
				"ever",
				"Due to the toils of many a well-fought day;");
		Random random = new Random();
		for (int i = 0; i < 10000; i++) {
			executorService.submit(() -> {
				String searchFor = searches.get(random.nextInt(searches.size()));
				Map<String, Collection<Integer>> res = searchService.searchForString(searchFor);
				res.forEach((s, integers) -> {
					integers.forEach(integer -> {
						assertThat(searchService.getFilePart(s, integer, searchFor.length())).isEqualTo(searchFor);
					});
				});
			});
		}
		executorService.shutdown();
		executorService.awaitTermination(10, TimeUnit.MINUTES);
		stopWatch.stop();
		log.info("Test completed in {} ms", stopWatch.getLastTaskTimeMillis());
	}

}
