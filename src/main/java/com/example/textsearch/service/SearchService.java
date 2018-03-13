package com.example.textsearch.service;

import com.example.textsearch.util.timelog.TimeMe;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.textsearch.service.CacheConfig.SEARCH_CACHE;

@Service
@Slf4j
public class SearchService {

    /*
    * Class to store index entry - essentially, word
    * */
    @Getter
    @RequiredArgsConstructor
    private static class WordEntry {
        @NonNull
        String source;
        //since we have full text in memory as Strings we can store only indexes
        @NonNull
        int wordHash;
        @NonNull
        int wordPos;
        @NonNull
        int wordLength;

    }

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    //It's important to know what would be the use case - do we expect really significant amount of files being searched?
    //Here I presume we won't have lots of files and we're ok to have O(num of files) component in our complexity - considering it as const
    //This lets us not bother with storing a source link on each indexed word object, and iterate over source entries instead
    //If this is not fine, it's not hard to slightly change implementation to have one index for all files, but will require some more memory
    private final Map<Integer, Collection<WordEntry>> index = new HashMap<>();

    //I presume we have enough heap to store all files as string (along with indexes)
    //as well as file sizes won't exceed Integer.MAX_VALUE characters.
    //If we cared about heap size we could, say, use file-based H2, or MapDB - to store similar data model off heap.
    private final Map<String, String> rawTextEntries = new HashMap<>();

    @TimeMe
    @CacheEvict(value = SEARCH_CACHE, allEntries = true)
    public void addSource(String name, InputStream stream) throws IOException {
        lock.writeLock().lock();
        try {
            if (rawTextEntries.containsKey(name)) {
                throw new IllegalStateException("Source with name " + name + " already added");
            }
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(stream));
            String rawText = parseStringStream(name, bufferedReader, wordEntry -> {
                index.computeIfAbsent(wordEntry.getWordHash(), w -> new ArrayList<>()).add(wordEntry);
            });
            if (rawText.isEmpty()) {
                throw new IllegalStateException("Won't add empty file");
            }
            rawTextEntries.put(name, rawText);
            log.info("Added fileEntry {}", name);
            log.info("Index size is {} sources, {} words", rawTextEntries.size(), index.values().stream().mapToInt(Collection::size).sum());
        } finally {
            lock.writeLock().unlock();
        }
    }

//    @TimeMe
    public String getFilePart(String name, int fromIndex, int length) {
        return doWithReadLock(() -> {
            String s = rawTextEntries.get(name);
            if (s == null) {
                return null;
            }
            int endIndex = fromIndex + length;
            endIndex = endIndex > s.length() ? s.length() : endIndex;
            int beginIndex = fromIndex < 0 ? 0 : fromIndex;
            return s.substring(beginIndex, endIndex);
        });
    }

    //we might consider some smart options like case insensitive search, or non-strict delimiters match,
    //but this will require some more work, especially with non-indexed search fallback - so not now :)
    @TimeMe
    @SneakyThrows
    @Cacheable(SEARCH_CACHE)
    public Map<String, Collection<Integer>> searchForString(String searchFor) {
        if (searchFor.length() < 3) {
            return Collections.emptyMap();
        }
        return doWithReadLock(() -> {
            Map<String, Collection<Integer>> res;
            List<WordEntry> searchForParsed = new ArrayList<>();
            StringReader reader = new StringReader(searchFor);
            parseStringStream("search", reader, searchForParsed::add);
            if (searchForParsed.size() < 3) {
                //bad case - cannot use word index
                //we have 1 or 2 words, hence potentially one or both may be partially matching,
                //so doesn't make sense to use index in this case - we'll have to do a full scan in any case
                //todo think a bit, maybe it does?
                log.info("Running non-indexed search for string {}", searchFor);
                res = nonIndexedSearch(searchFor);
            } else {
                //3 or more words, hence we can rely on index for any word except 1st and last
                //if there is no index match, then the string is not found
                log.info("Running indexed search for string <{}>", searchFor);
                res = new HashMap<>();
                //now we look for all words index match
                //(except 1st and last - they may be matching partially)
                List<Pair<WordEntry, Collection<WordEntry>>> foundIndexEntries = searchForParsed.stream().skip(1).limit(searchForParsed.size() - 2)
                        .filter(we -> we.getWordLength() > 0)
                        .map(we -> new ImmutablePair<>(we, index.get(we.getWordHash()))).collect(Collectors.toList());
                //proceed only if all middle words are found in index, otherwise no match
                if (foundIndexEntries.stream().allMatch(p -> p.getValue() != null && p.getValue().size() > 0)) {
                    //now we find the word in search string with least index match count
                    Pair<WordEntry, Collection<WordEntry>> minCountIndexMatch = foundIndexEntries.stream()
                            .min(Comparator.comparing(t -> t.getRight().size())).orElse(null);
                    if (minCountIndexMatch != null) {
                        log.debug("Found index entry for word <{}>, {} matches", getIndexedWord(searchFor, minCountIndexMatch.getKey()), minCountIndexMatch.getValue().size());
                        minCountIndexMatch.getValue().forEach(idxWordEntry -> {
                            String s = rawTextEntries.get(idxWordEntry.getSource());
                            int checkedRangeStart = idxWordEntry.getWordPos() - minCountIndexMatch.getKey().getWordPos();
                            if (charsEquals(s, checkedRangeStart, searchFor)) {
                                res.computeIfAbsent(idxWordEntry.getSource(), name -> new TreeSet<>()).add(checkedRangeStart);
                            }
                        });
                    }
                }
            }
            if (res.size() > 0) {
                log.info("Found {} matches for string <{}>", res.values().stream().mapToInt(Collection::size).sum(), searchFor);
            } else {
                log.info("Found no matches for string <{}>", searchFor);
            }
            return res;
        });
    }

    private static boolean charsEquals(String str, int fromIndex, String compareTo) {
        if (fromIndex < 0 || fromIndex + compareTo.length() > str.length()) {
            return false;
        }
        for (int i = 0; i < compareTo.length(); i++) {
            if (str.charAt(fromIndex + i) != compareTo.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /*
    * when doing non-indexed search we can use some efficient
    * */
    private Map<String, Collection<Integer>> nonIndexedSearch(String searchFor) {
        Map<String, Collection<Integer>> res = new HashMap<>();
        rawTextEntries.forEach((sourceEntry, s) -> {
            Collection<Integer> c = kmpSearch(searchFor.toCharArray(), s);
            if (c.size() > 0)
            res.computeIfAbsent(sourceEntry, name -> new TreeSet<>()).addAll(c);
        });
        return res;
    }


    /*KMP search, implementation found in Wikipedia*/
    private static Collection<Integer> kmpSearch(char[] pattern, String text) {
        int[] pfl = pfl(pattern);
        int[] indexes = new int[text.length()];
        int size = 0;
        int k = 0;
        for (int i = 0; i < text.length(); ++i) {
            while (pattern[k] != text.charAt(i) && k > 0) {
                k = pfl[k - 1];
            }
            if (pattern[k] == text.charAt(i)) {
                k = k + 1;
                if (k == pattern.length) {
                    indexes[size] = i + 1 - k;
                    size += 1;
                    k = pfl[k - 1];
                }
            } else {
                k = 0;
            }
        }
        return Arrays.stream(Arrays.copyOfRange(indexes, 0, size)).boxed().collect(Collectors.toList());
    }

    private static int[] pfl(char[] text) {
        int[] pfl = new int[text.length];
        pfl[0] = 0;

        for (int i = 1; i < text.length; ++i) {
            int k = pfl[i - 1];
            while (text[k] != text[i] && k > 0) {
                k = pfl[k - 1];
            }
            if (text[k] == text[i]) {
                pfl[i] = k + 1;
            } else {
                pfl[i] = 0;
            }
        }

        return pfl;
    }

    public String getSource(String name) {
        return doWithReadLock(() -> rawTextEntries.get(name));
    }

    public Collection<String> getStoredSourceNames() {
        return doWithReadLock(() -> Collections.unmodifiableSet(rawTextEntries.keySet()));
    }


    /*
    * In this method we parse text, building index data for each word
    * */
    @SneakyThrows
    private String parseStringStream(String source, Reader bufferedReader, Consumer<WordEntry> onWordEntry) {
        int ch = bufferedReader.read();
        if (ch == -1) {
            //is sourceEntry empty?
            return "";
        }
        StringBuilder wholeThing = new StringBuilder();
        int wordsParsed = 0;
        boolean wasWord = isWordChar(ch);
        StringBuilder word = new StringBuilder();
        int wordPos = -1;
        if (wasWord) {
            wordPos = 0;
            word.appendCodePoint(ch);
        }
        wholeThing.appendCodePoint(ch);
        int pos = 0;
        while ((ch = bufferedReader.read()) != -1) {
            wholeThing.appendCodePoint(ch);
            pos++;
            if (isWordChar(ch)) {
                if (!wasWord) {
                    //word+delimiter ready
                    wordsParsed++;
                    String w = word.toString();
                    int key = w.hashCode();
                    WordEntry wordEntry = new WordEntry(source, key, wordPos, w.length());
                    onWordEntry.accept(wordEntry);
                    word = new StringBuilder();
                    wordPos = pos;
                }
                word.appendCodePoint(ch);
                wasWord = true;
            } else {
                wasWord = false;

            }
        }
        wordsParsed++;
        String w = word.toString();
        int key = w.hashCode();
        WordEntry wordEntry = new WordEntry(source, key, wordPos, w.length());
        onWordEntry.accept(wordEntry);
        log.debug("Parse completed, parsed {} chars, {} words", pos, wordsParsed);
        return wholeThing.toString();
    }

    private boolean isWordChar(int codePoint) {
        return Character.isAlphabetic(codePoint) || Character.isDigit(codePoint);
    }

    private String getIndexedWord(String source, WordEntry wordEntry) {
        if (wordEntry.wordLength > 0 && wordEntry.wordPos > -1) {
            return source.substring(wordEntry.getWordPos(), wordEntry.getWordPos() + wordEntry.getWordLength());
        } else {
            return null;
        }
    }

    private <T> T doWithReadLock(Supplier<T> delegate) {
        lock.readLock().lock();
        try {
            return delegate.get();
        } finally {
            lock.readLock().unlock();
        }

}

}
