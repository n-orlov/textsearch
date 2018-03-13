package com.example.textsearch.web;

import com.example.textsearch.service.SearchService;
import lombok.AllArgsConstructor;
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

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

@Controller
@RequestMapping("/")
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
                                   @RequestParam Integer matchIndex, @RequestParam Integer radius,
                                   Model model) throws IOException {
        //String filePart = searchService.getSource()getFilePart(name, matchIndex - radius, searchFor.length() + radius * 2);
        if (searchService.getSource(name) == null) {
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

    @GetMapping(value = "/download/{name}")
    public ResponseEntity<Resource> downloadFile(@PathVariable("name") String name) throws IOException {
        String source = searchService.getSource(name);
        if (source == null) {
            throw new ResourceNotFoundException("File " + name + " not found");
        }
        ByteArrayResource resource = new ByteArrayResource(source.getBytes("UTF8"));
        return ResponseEntity.ok()
                .contentLength(resource.contentLength())
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .body(resource);
    }

    @PostMapping("/upload")
    public String handleFileUpload(@RequestParam("file") MultipartFile uploaded,
                                   RedirectAttributes redirectAttributes) throws IOException {
        String name = uploaded.getOriginalFilename();
        if (name == null || name.isEmpty()) {
            throw new BadRequestException("Cannot get file name");
        }
        try {
            searchService.addSource(name, uploaded.getInputStream());
            redirectAttributes.addFlashAttribute("message",
                    "You successfully uploaded: " + uploaded.getOriginalFilename());
            return "redirect:/upload";
        } catch (Exception e) {
            log.error("Error caught when uploading file", e);
            throw new BadRequestException(e.getMessage());
        }
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
