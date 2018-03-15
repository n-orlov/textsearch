package com.example.textsearchbox.service;

import com.example.textsearchbox.util.timelog.TimeMe;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.*;
import java.lang.ref.SoftReference;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.example.textsearchbox.service.CacheConfig.SEARCH_CACHE;
import static java.util.stream.Collectors.toList;

@Service
@Slf4j
public class SearchService {

    @Getter
    private class FileEntry {
        final File file;
        final long length;
        private volatile SoftReference<String> content;

        @SneakyThrows
        Reader getReader() {
            return canLoadContent() ? new StringReader(getContent()) : new BufferedReader(new FileReader(getFile()));
        }

        boolean canLoadContent() {
            return length <= loadFileToMemorySizeLimit;
        }

        boolean canBuildIndex() {
            //we can also think of applying some smart logic here,
            // like trying to analyze file content to see if having index makes sense
            return length <= buildIndexSizeLimit;
        }

        @SneakyThrows
        String getFilePart(int fromIndex, int length) {
            if (canLoadContent()) {
                String s = getContent();
                int endIndex = fromIndex + length;
                endIndex = endIndex > s.length() ? s.length() : endIndex;
                int beginIndex = fromIndex < 0 ? 0 : fromIndex;
                return s.substring(beginIndex, endIndex);
            } else {
                long endIndex = fromIndex + length;
                endIndex = endIndex > this.getLength() ? this.getLength() : endIndex;
                int beginIndex = fromIndex < 0 ? 0 : fromIndex;
                int fragmentLength = (int) (endIndex - beginIndex);
                char[] buf = new char[fragmentLength];
                try (Reader reader = getReader()) {
                    reader.skip(beginIndex);
                    reader.read(buf, 0, fragmentLength);
                }
                return new String(buf);
            }
        }

        @SneakyThrows
        String getContent() {
            if (!canLoadContent()) {
                throw new IllegalStateException("File is too large to be returned as String");
            }
            String str = content == null ? null : content.get();
            if (str == null) {
                synchronized (this) {
                    str = content == null ? null : content.get();
                    if (str == null) {
                        str = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                        content = new SoftReference<>(str);
                    }
                }
            }
            return str;
        }

        FileEntry(File file) {
            this.file = file;
            this.length = file.length();
        }
    }

    /*
    * Class to store index entry - essentially, a word
    * */
    @Getter
    @RequiredArgsConstructor
    private static class WordEntry implements Serializable {
        @NonNull
        String source;
        @NonNull
        int wordHash;
        @NonNull
        int wordPos;
        @NonNull
        int wordLength;
    }

    private Integer loadFileToMemorySizeLimit;
    private Integer buildIndexSizeLimit;

    public SearchService(
            @Value("${loadFileToMemorySizeLimit:10000000}") Integer loadFileToMemorySizeLimit,
            @Value("${buildIndexSizeLimit:10000000}") Integer buildIndexSizeLimit
    ) {
        this.loadFileToMemorySizeLimit = loadFileToMemorySizeLimit;
        this.buildIndexSizeLimit = buildIndexSizeLimit;
    }

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, FileEntry> fileEntries = new HashMap<>();

    //inmemory index
    //doesn't need to be concurrent, locks take care of safe publication
    private final Map<Integer, Collection<WordEntry>> index = new HashMap<>();

    @TimeMe
    @CacheEvict(value = SEARCH_CACHE, allEntries = true)
    public void addSource(String name, File file) throws IOException {
        lock.writeLock().lock();
        try {
            Map<Integer, Collection<WordEntry>> index;
            if (fileEntries.containsKey(name)) {
                throw new IllegalStateException("Source with name " + name + " already added");
            }
            long length = file.length();
            if (length == 0) {
                throw new IllegalStateException("Won't add empty file " + name);
            }
            FileEntry fileEntry = new FileEntry(file);
            if (fileEntry.canBuildIndex()) {
                index = new HashMap<>();
                try (Reader reader = fileEntry.getReader()) {
                    parseStringStream(name, reader, wordEntry -> {
                        index.computeIfAbsent(wordEntry.getWordHash(), w -> new ArrayList<>()).add(wordEntry);
                    });
                }
                index.forEach((n, words) -> this.index.computeIfAbsent(n, s -> new ArrayList<>()).addAll(words));
            }
            fileEntries.put(name, fileEntry);
            log.info("Added fileEntry {}, indexed: {}, loaded into memory: {}", name, fileEntry.canBuildIndex(), fileEntry.canLoadContent());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String getFilePart(String name, int fromIndex, int length) {
        return doWithReadLock(() -> {
            FileEntry fileEntry = fileEntries.get(name);
            return fileEntry == null ? null : fileEntry.getFilePart(fromIndex, length);
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
            StringReader sReader = new StringReader(searchFor);
            parseStringStream("search", sReader, searchForParsed::add);
            if (searchForParsed.size() < 3) {
                //bad case - cannot use word index
                //we have 1 or 2 words, hence potentially one or both may be partially matching,
                //so doesn't make sense to use index in this case - we'll have to do a full scan in any case
                //todo think a bit, maybe it does?
                log.debug("Running non-indexed search for string {}", searchFor);
                res = nonIndexedSearch(searchFor, false);
            } else {
                //3 or more words, hence we can rely on index for any word except 1st and last
                //if there is no index match, then the string is not found
                //still need to run non-indexed search on sources without index
                res = new HashMap<>();
                log.debug("Running non-indexed search for string <{}>", searchFor);
                res.putAll(nonIndexedSearch(searchFor, true));
                log.debug("Running indexed search for string <{}>", searchFor);
                //now we look for all words index match
                //(except 1st and last - they may be matching partially)
                List<Pair<WordEntry, Collection<WordEntry>>> foundIndexEntries = searchForParsed.stream().skip(1).limit(searchForParsed.size() - 2)
                        .filter(we -> we.getWordLength() > 0)
                        .map(we -> new ImmutablePair<>(we, index.get(we.getWordHash()))).collect(toList());
                //proceed only if all middle words are found in index, otherwise no match
                if (foundIndexEntries.stream().allMatch(p -> p.getValue() != null && !p.getValue().isEmpty())) {
                    //now we find the word in search string with least index match count
                    Pair<WordEntry, Collection<WordEntry>> minCountIndexMatch = foundIndexEntries.stream()
                            .min(Comparator.comparing(t -> t.getRight().size())).orElse(null);
                    if (minCountIndexMatch != null) {
                        log.trace("Found index entry for word <{}>, {} matches", getIndexedWord(searchFor, minCountIndexMatch.getKey()), minCountIndexMatch.getValue().size());
                        minCountIndexMatch.getValue().stream()
                                .collect(Collectors.groupingBy(WordEntry::getSource)).forEach((String s, List<WordEntry> wordEntries) -> {
                            FileEntry fileEntry = fileEntries.get(s);
                            if (fileEntry.canLoadContent()) {
                                //in-memory index matches verification
                                String content = fileEntry.getContent();
                                wordEntries.forEach(idxWordEntry -> {
                                    int checkedRangeStart = idxWordEntry.getWordPos() - minCountIndexMatch.getKey().getWordPos();
                                    if (charsEquals(content, checkedRangeStart, searchFor)) {
                                        res.computeIfAbsent(idxWordEntry.getSource(), name -> new TreeSet<>()).add(checkedRangeStart);
                                    }
                                });
                            } else {
                                //walk-through-file index matches verification
                                try (Reader reader = fileEntry.getReader()) {
                                    int curFilePos = 0;
                                    char[] seArray = searchFor.toCharArray();
                                    char[] prev = null;
                                    List<WordEntry> wordPositions = wordEntries.stream().sorted(Comparator.comparing(WordEntry::getWordPos)).collect(toList());
                                    for (WordEntry idxWordEntry : wordPositions) {
                                        try {
                                            char[] buf = new char[searchFor.length()];
                                            int checkedRangeStart = idxWordEntry.getWordPos() - minCountIndexMatch.getKey().getWordPos();
                                            int toSkip = checkedRangeStart - curFilePos;
                                            if (toSkip < 0 && prev == null) {
                                                continue;
                                            }
                                            int read;
                                            if (toSkip < 0) {
                                                int toRead = searchFor.length() + toSkip;
                                                CharBuffer wrapPrev = CharBuffer.wrap(prev);
                                                wrapPrev.position(toRead);
                                                CharBuffer.wrap(buf).put(wrapPrev.slice());
                                                read = reader.read(buf, -toSkip, toRead);
                                            } else {
                                                long skip = reader.skip(toSkip);
                                                curFilePos += skip;
                                                read = reader.read(buf, 0, searchFor.length());
                                            }
                                            if (read == searchFor.length() && Arrays.equals(buf, seArray)) {
                                                res.computeIfAbsent(idxWordEntry.getSource(), name -> new TreeSet<>()).add(checkedRangeStart);
                                            }
                                            curFilePos += read;
                                            prev = buf;
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
                    }
                }
            }
            if (res.size() > 0) {
                log.debug("Found {} matches for string <{}>", res.values().stream().mapToInt(Collection::size).sum(), searchFor);
            } else {
                log.debug("Found no matches for string <{}>", searchFor);
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
    * when doing non-indexed search we can use some efficient search algorithm
    * KMP would work, for example. And we can scan files in parallel.
    * */
    private Map<String, Collection<Integer>> nonIndexedSearch(String searchFor, boolean onlyOnNonIndexed) {
        return fileEntries.entrySet().parallelStream().filter(se -> !(onlyOnNonIndexed && se.getValue().canBuildIndex())).map(
                se -> new ImmutablePair<>(se.getKey(), inFileSearch(searchFor, se.getValue())))
                .filter(se -> se != null && se.getValue().size() > 0)
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
    }

    @SneakyThrows
    private static Collection<Integer> inFileSearch(String pattern, FileEntry file) {
        if (file.canLoadContent()) {
            return  kmpSearch(pattern, file.getContent());
        }
        Collection<Integer> res = new ArrayList<>();
        int bufLength = Integer.max(pattern.length() * 2, 64000);
        int currentBufOffset = 0;
        char[] buf = new char[bufLength];
        int actualBufSize = 0;
        char[] prev = new char[pattern.length() - 1];
        try (Reader reader = file.getReader()) {
            int read = reader.read(buf, 0, bufLength);
            actualBufSize = read;
            while (read > 0) {
                int cbo = currentBufOffset;
                res.addAll(kmpSearch(pattern, new String(actualBufSize == bufLength ? buf : Arrays.copyOfRange(buf, 0, actualBufSize)))
                        .stream().map(p -> p + cbo).collect(toList()));
                System.arraycopy(buf, bufLength - pattern.length() + 1, prev, 0, pattern.length() - 1);
                read = reader.read(buf, pattern.length() - 1, bufLength - pattern.length() + 1);
                currentBufOffset += bufLength - pattern.length() + 1;
                actualBufSize = read + pattern.length() - 1;

            }
        }
        return res;
    }

    /*KMP search, implementation found in Wikipedia*/
    private static Collection<Integer> kmpSearch(String pattern, String text) {
        int[] pfl = pfl(pattern);
        int[] indexes = new int[text.length()];
        int size = 0;
        int k = 0;
        for (int i = 0; i < text.length(); ++i) {
            while (pattern.charAt(k) != text.charAt(i) && k > 0) {
                k = pfl[k - 1];
            }
            if (pattern.charAt(k) == text.charAt(i)) {
                k = k + 1;
                if (k == pattern.length()) {
                    indexes[size] = i + 1 - k;
                    size += 1;
                    k = pfl[k - 1];
                }
            } else {
                k = 0;
            }
        }
        return Arrays.stream(Arrays.copyOfRange(indexes, 0, size)).boxed().collect(toList());
    }

    private static int[] pfl(String text) {
        int[] pfl = new int[text.length()];
        pfl[0] = 0;

        for (int i = 1; i < text.length(); ++i) {
            int k = pfl[i - 1];
            while (text.charAt(k) != text.charAt(i) && k > 0) {
                k = pfl[k - 1];
            }
            if (text.charAt(k) == text.charAt(i)) {
                pfl[i] = k + 1;
            } else {
                pfl[i] = 0;
            }
        }

        return pfl;
    }

    public InputStream getSource(String name) {
        return doWithReadLock(() -> {
            try {
                FileEntry fileEntry = fileEntries.get(name);
                return fileEntry.canLoadContent() ? new ByteArrayInputStream(fileEntry.getContent().getBytes()) : new BufferedInputStream(new FileInputStream(fileEntry.getFile()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public Collection<String> getStoredSourceNames() {
        return doWithReadLock(() -> Collections.unmodifiableSet(fileEntries.keySet()));
    }

    /*
    * In this method we parse text, building index data for each word
    * */
    @SneakyThrows
    private static void parseStringStream(String source, Reader reader, Consumer<WordEntry> onWordEntry) {
        int ch = reader.read();
        if (ch == -1) {
            //is sourceEntry empty?
            return;
        }
        int wordsParsed = 0;
        boolean wasWord = isWordChar(ch);
        StringBuilder word = new StringBuilder();
        int wordPos = -1;
        if (wasWord) {
            wordPos = 0;
            word.appendCodePoint(ch);
        }
        int pos = 0;
        while ((ch = reader.read()) != -1) {
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
            if (pos % 1_000_000 == 0) { //log every 1M chars
                log.trace("Parsed {} chars / {} words so far", pos, wordsParsed);
            }
        }
        wordsParsed++;
        String w = word.toString();
        int key = w.hashCode();
        WordEntry wordEntry = new WordEntry(source, key, wordPos, w.length());
        onWordEntry.accept(wordEntry);
        log.trace("Parse completed, parsed {} chars, {} words", pos, wordsParsed);
    }

    private static boolean isWordChar(int codePoint) {
        return Character.isAlphabetic(codePoint) || Character.isDigit(codePoint);
    }

    private static String getIndexedWord(String source, WordEntry wordEntry) {
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
