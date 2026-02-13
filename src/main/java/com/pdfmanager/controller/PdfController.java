package com.pdfmanager.controller;

import com.pdfmanager.service.PdfService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/pdf")
public class PdfController {

    private final PdfService pdfService;

    public PdfController(PdfService pdfService) {
        this.pdfService = pdfService;
    }

    @PostMapping("/page-count")
    public ResponseEntity<?> getPageCount(@RequestParam("file") MultipartFile file) {
        try {
            byte[] pdfData = file.getBytes();
            int count = pdfService.getPageCount(pdfData);
            return ResponseEntity.ok().body("{\"pageCount\": " + count + "}");
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("{\"error\": \"PDF 파일을 읽을 수 없습니다: " + e.getMessage() + "\"}");
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<?> deletePages(
            @RequestParam("file") MultipartFile file,
            @RequestParam("pages") String pages) {
        try {
            byte[] pdfData = file.getBytes();
            List<Integer> pageNumbers = parsePageNumbers(pages);
            byte[] result = pdfService.deletePages(pdfData, pageNumbers);
            return buildPdfResponse(result, "deleted_" + file.getOriginalFilename());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("{\"error\": \"" + e.getMessage() + "\"}");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("{\"error\": \"PDF 처리 중 오류 발생: " + e.getMessage() + "\"}");
        }
    }

    @PostMapping("/insert")
    public ResponseEntity<?> insertPages(
            @RequestParam("targetFile") MultipartFile targetFile,
            @RequestParam("insertFile") MultipartFile insertFile,
            @RequestParam("insertAt") int insertAt) {
        try {
            byte[] targetData = targetFile.getBytes();
            byte[] insertData = insertFile.getBytes();
            byte[] result = pdfService.insertPages(targetData, insertData, insertAt);
            return buildPdfResponse(result, "inserted_" + targetFile.getOriginalFilename());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("{\"error\": \"" + e.getMessage() + "\"}");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("{\"error\": \"PDF 처리 중 오류 발생: " + e.getMessage() + "\"}");
        }
    }

    @PostMapping("/replace")
    public ResponseEntity<?> replacePages(
            @RequestParam("targetFile") MultipartFile targetFile,
            @RequestParam("replaceFile") MultipartFile replaceFile,
            @RequestParam("replacePages") String replacePages) {
        try {
            byte[] targetData = targetFile.getBytes();
            byte[] replaceData = replaceFile.getBytes();
            List<Integer> pageNumbers = parsePageNumbers(replacePages);
            byte[] result = pdfService.deleteAndInsert(targetData, replaceData, pageNumbers);
            return buildPdfResponse(result, "replaced_" + targetFile.getOriginalFilename());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("{\"error\": \"" + e.getMessage() + "\"}");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("{\"error\": \"PDF 처리 중 오류 발생: " + e.getMessage() + "\"}");
        }
    }

    @PostMapping("/delete-and-insert")
    public ResponseEntity<?> deleteAndInsert(
            @RequestParam("targetFile") MultipartFile targetFile,
            @RequestParam("insertFile") MultipartFile insertFile,
            @RequestParam("deletePages") String deletePages) {
        try {
            byte[] targetData = targetFile.getBytes();
            byte[] insertData = insertFile.getBytes();
            List<Integer> pageNumbers = parsePageNumbers(deletePages);
            byte[] result = pdfService.deleteAndInsert(targetData, insertData, pageNumbers);
            return buildPdfResponse(result, "modified_" + targetFile.getOriginalFilename());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("{\"error\": \"" + e.getMessage() + "\"}");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("{\"error\": \"PDF 처리 중 오류 발생: " + e.getMessage() + "\"}");
        }
    }

    @PostMapping("/split")
    public ResponseEntity<?> splitPages(
            @RequestParam("file") MultipartFile file,
            @RequestParam("rules") String rulesJson) {
        try {
            byte[] pdfData = file.getBytes();
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, String>> rules = mapper.readValue(rulesJson,
                    new TypeReference<List<Map<String, String>>>() {});

            if (rules.isEmpty()) {
                return ResponseEntity.badRequest().body("{\"error\": \"분리 규칙이 비어있습니다.\"}");
            }

            ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(zipOut)) {
                for (Map<String, String> rule : rules) {
                    String pages = rule.get("pages");
                    String filename = rule.get("filename");
                    if (pages == null || pages.trim().isEmpty() || filename == null || filename.trim().isEmpty()) {
                        return ResponseEntity.badRequest().body("{\"error\": \"각 규칙에 페이지와 파일명이 필요합니다.\"}");
                    }

                    List<Integer> pageNumbers = parsePageNumbers(pages);
                    byte[] extractedPdf = pdfService.extractPages(pdfData, pageNumbers);

                    String pdfFilename = filename.trim().endsWith(".pdf") ? filename.trim() : filename.trim() + ".pdf";
                    ZipEntry entry = new ZipEntry(pdfFilename);
                    zos.putNextEntry(entry);
                    zos.write(extractedPdf);
                    zos.closeEntry();
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("application/zip"));
            headers.setContentDispositionFormData("attachment", "split_pages.zip");
            byte[] zipData = zipOut.toByteArray();
            headers.setContentLength(zipData.length);
            return ResponseEntity.ok().headers(headers).body(zipData);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("{\"error\": \"" + e.getMessage() + "\"}");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("{\"error\": \"PDF 처리 중 오류 발생: " + e.getMessage() + "\"}");
        }
    }

    private List<Integer> parsePageNumbers(String pages) {
        try {
            List<Integer> result = new ArrayList<>();
            for (String part : pages.split(",")) {
                part = part.trim();
                if (part.contains("~")) {
                    String[] range = part.split("~", 2);
                    int start = Integer.parseInt(range[0].trim());
                    int end = Integer.parseInt(range[1].trim());
                    if (start > end) {
                        throw new IllegalArgumentException(
                                "범위의 시작 값이 끝 값보다 클 수 없습니다: " + start + "~" + end);
                    }
                    for (int i = start; i <= end; i++) {
                        result.add(i);
                    }
                } else {
                    result.add(Integer.parseInt(part));
                }
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "페이지 번호 형식이 올바르지 않습니다. (예: 1,3,5 또는 1~5 또는 1~3,7,9~11)");
        }
    }

    private ResponseEntity<byte[]> buildPdfResponse(byte[] pdfData, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(pdfData.length);
        return ResponseEntity.ok().headers(headers).body(pdfData);
    }
}
