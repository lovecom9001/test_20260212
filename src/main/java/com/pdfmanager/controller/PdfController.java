package com.pdfmanager.controller;

import com.pdfmanager.service.PdfService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    private List<Integer> parsePageNumbers(String pages) {
        try {
            return Arrays.stream(pages.split(","))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("페이지 번호 형식이 올바르지 않습니다. 쉼표로 구분된 숫자를 입력하세요. (예: 1,3,5)");
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
