package com.example.textsearchbox.web;

import com.example.textsearchbox.service.SearchService;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Scanner;

@Controller
@RequestMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
@Slf4j
public class TextsearchController {

    private SearchService searchService;

    @Autowired
    public TextsearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @ModelAttribute("files")
    public Collection<String> getFiles() {
        return searchService.getStoredSourceNames();
    }

    @GetMapping("/")
    public String getSearchForm() throws IOException {
        return "searchForm";
    }

    @PostMapping("/")
    public String runSearch(@RequestParam String searchFor, Model model) throws IOException {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("search");
        Map<String, Collection<Integer>> result = searchService.searchForString(searchFor);
        stopWatch.stop();

        model.addAttribute("searchFor", searchFor);
        model.addAttribute("matchCount", result.values().stream().mapToLong(Collection::size).sum());
        model.addAttribute("searchTimeMillis", stopWatch.getLastTaskTimeMillis());
        model.addAttribute("searchResults", result);
        return "searchForm";
    }

    @GetMapping("/upload")
    public String listUploadedFiles() throws IOException {
        return "uploadForm";
    }

    @GetMapping("/fragment")
    public String viewFileFragment(@RequestParam String name, @RequestParam String searchFor,
                                   @RequestParam Integer matchIndex, @RequestParam(defaultValue = "100") Integer radius,
                                   Model model) throws IOException {
        if (!searchService.getStoredSourceNames().contains(name)) {
            throw new ResourceNotFoundException("File " + name + " not found");
        }
        model.addAttribute("name", name);
        model.addAttribute("searchFor", searchFor);
        model.addAttribute("before", searchService.getFilePart(name, matchIndex - radius, radius));
        model.addAttribute("after", searchService.getFilePart(name, matchIndex + searchFor.length(), radius));
        model.addAttribute("matchIndex", matchIndex);
        model.addAttribute("radius", radius);

        return "textFragment";
    }

    @GetMapping(value = "/download/{name}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable("name") String name) throws IOException {
        InputStream source = searchService.getSource(name);
        if (source == null) {
            throw new ResourceNotFoundException("File " + name + " not found");
        }
        InputStreamResource resource = new InputStreamResource(source);
        return ResponseEntity.ok()
                .body(resource);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String handleFileUpload(@RequestParam("file") MultipartFile uploadedFiles[],
                                   RedirectAttributes redirectAttributes) throws IOException {
        Collection<String> added = new ArrayList<>();
        Collection<String> errors = new ArrayList<>();
        for (MultipartFile uploaded : uploadedFiles) {
            String name = uploaded.getOriginalFilename();
            File tempFile = File.createTempFile("txtSearch", null);
            uploaded.transferTo(tempFile);
            if (name == null || name.isEmpty()) {
                errors.add("Cannot get file name");
                continue;
            }
            try {
                searchService.addSource(name, tempFile);
                added.add(name);
            } catch (Exception e) {
                log.error("Error caught when uploading file", e);
                errors.add(e.getMessage());
            }
        }
        redirectAttributes.addFlashAttribute("message",
                "You successfully uploaded: " + String.join(", ", added));
        redirectAttributes.addFlashAttribute("errors",
                "Errors caught: " + String.join(", ", errors));
        return "redirect:/upload";
    }

    @ResponseStatus(value = HttpStatus.NOT_FOUND)
    @NoArgsConstructor
    public static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) {
            super(message);
        }
    }
    @ResponseStatus(value = HttpStatus.BAD_REQUEST)
    @NoArgsConstructor
    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }
}
