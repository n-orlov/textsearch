package com.example.textsearchbox;

import com.example.textsearchbox.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StopWatch;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("nocache")
@Slf4j
@TestPropertySource(properties = {"loadFileToMemorySizeLimit=10000000", "buildIndexSizeLimit=10000000", "spring.cache.type=none"})
public class PerformanceTest {

	public static final int NUM_SEARCHES = 1000;
	public static final int N_THREADS = 4;
	public static final int TIMEOUT = 0;

	private ExecutorService executorService = Executors.newFixedThreadPool(N_THREADS);

	@Autowired
	private SearchService searchService;

	@Test(timeout = TIMEOUT)
	@Ignore("this test is for manual execution")
	public void runLoadTest() throws Exception {
		Collection<Long> timings = Collections.synchronizedList(new ArrayList<>());
		StopWatch stopWatchTotal = new StopWatch();
		stopWatchTotal.start();
		searchService.addSource("texts/war_and_peace.txt", ResourceUtils.getFile("classpath:texts/war_and_peace.txt"));
		searchService.addSource("texts/pride_and_prejudice.txt", ResourceUtils.getFile("classpath:texts/pride_and_prejudice.txt"));
		searchService.addSource("texts/Iliad.txt", ResourceUtils.getFile("classpath:texts/Iliad.txt"));
		List<String> searches = Arrays.asList(
				"Denísov"
				,"Where are they off to now"
				,"no further; for on entering that place"
				,"sincere desire not to"
				,"It is mutiny—seizing the transport"
				,"Where are they off to now"
				,"instantly"
				,"obviously false match, indexable"
				,"obviouslyfalsematchnonindexed"
				,"ever"
				,"Due to the toils of many a well-fought day;"
		);
		Random random = new Random();
		for (int i = 0; i < NUM_SEARCHES; i++) {
			executorService.submit(() -> {
				String searchFor = searches.get(random.nextInt(searches.size()));
				StopWatch stopWatch = new StopWatch();
				stopWatch.start();
				Map<String, Collection<Integer>> res = searchService.searchForString(searchFor);
				stopWatch.stop();
				timings.add(stopWatch.getLastTaskTimeMillis());
				res.forEach((s, integers) -> {
						assertThat(searchService.getFilePart(s, integers.iterator().next(), searchFor.length())).isEqualTo(searchFor);
				});
			});
		}
		executorService.shutdown();
		executorService.awaitTermination(10, TimeUnit.MINUTES);
		stopWatchTotal.stop();
		log.info("Test completed in {} ms", stopWatchTotal.getLastTaskTimeMillis());
		log.info("Average search time is {} ms", timings.stream().mapToLong(t -> t).average().orElse(0));
	}

}
