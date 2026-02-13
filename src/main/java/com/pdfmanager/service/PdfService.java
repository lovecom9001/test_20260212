package com.pdfmanager.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class PdfService {

    /**
     * PDF에서 특정 페이지들을 삭제한다.
     *
     * @param pdfData      원본 PDF 바이트 배열
     * @param pageNumbers  삭제할 페이지 번호 목록 (1-based)
     * @return 페이지가 삭제된 PDF 바이트 배열
     */
    public byte[] deletePages(byte[] pdfData, List<Integer> pageNumbers) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfData)) {
            int totalPages = document.getNumberOfPages();
            validatePageNumbers(pageNumbers, totalPages);

            List<Integer> sorted = new ArrayList<>(pageNumbers);
            Collections.sort(sorted, Collections.reverseOrder());

            for (int pageNum : sorted) {
                document.removePage(pageNum - 1);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * PDF의 특정 위치에 다른 PDF의 페이지들을 삽입한다.
     *
     * @param targetPdfData  대상 PDF 바이트 배열
     * @param insertPdfData  삽입할 PDF 바이트 배열
     * @param insertAt       삽입할 위치 (1-based, 이 페이지 번호 앞에 삽입)
     * @return 페이지가 삽입된 PDF 바이트 배열
     */
    public byte[] insertPages(byte[] targetPdfData, byte[] insertPdfData, int insertAt) throws IOException {
        try (PDDocument targetDoc = Loader.loadPDF(targetPdfData);
             PDDocument insertDoc = Loader.loadPDF(insertPdfData)) {

            int totalPages = targetDoc.getNumberOfPages();
            if (insertAt < 1 || insertAt > totalPages + 1) {
                throw new IllegalArgumentException(
                        "삽입 위치는 1에서 " + (totalPages + 1) + " 사이여야 합니다. 입력값: " + insertAt);
            }

            int insertIndex = insertAt - 1;
            for (int i = 0; i < insertDoc.getNumberOfPages(); i++) {
                PDPage page = insertDoc.getPages().get(i);
                PDPage importedPage = targetDoc.importPage(page);
                targetDoc.getPages().insertBefore(importedPage, targetDoc.getPages().get(targetDoc.getNumberOfPages() - 1));
            }

            // importPage는 문서 끝에 추가하므로, 올바른 위치로 재배치
            reorderInsertedPages(targetDoc, totalPages, insertDoc.getNumberOfPages(), insertIndex);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            targetDoc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * PDF에서 특정 페이지들을 삭제하고, 삭제된 위치에 다른 PDF의 페이지들을 삽입한다.
     *
     * @param targetPdfData   대상 PDF 바이트 배열
     * @param insertPdfData   삽입할 PDF 바이트 배열
     * @param deletePages     삭제할 페이지 번호 목록 (1-based)
     * @return 결과 PDF 바이트 배열
     */
    public byte[] deleteAndInsert(byte[] targetPdfData, byte[] insertPdfData, List<Integer> deletePages) throws IOException {
        try (PDDocument targetDoc = Loader.loadPDF(targetPdfData);
             PDDocument insertDoc = Loader.loadPDF(insertPdfData)) {

            int totalPages = targetDoc.getNumberOfPages();
            validatePageNumbers(deletePages, totalPages);

            List<Integer> sorted = new ArrayList<>(deletePages);
            Collections.sort(sorted);
            int insertPosition = sorted.get(0) - 1; // 0-based index, 첫 번째 삭제 페이지 위치

            // 삭제할 페이지를 역순으로 제거
            List<Integer> descSorted = new ArrayList<>(deletePages);
            Collections.sort(descSorted, Collections.reverseOrder());
            for (int pageNum : descSorted) {
                targetDoc.removePage(pageNum - 1);
            }

            int pagesAfterDelete = targetDoc.getNumberOfPages();
            if (insertPosition > pagesAfterDelete) {
                insertPosition = pagesAfterDelete;
            }

            // 삽입할 페이지들을 가져와서 정확한 위치에 삽입
            List<PDPage> pagesToInsert = new ArrayList<>();
            for (int i = 0; i < insertDoc.getNumberOfPages(); i++) {
                pagesToInsert.add(insertDoc.getPages().get(i));
            }

            for (int i = 0; i < pagesToInsert.size(); i++) {
                PDPage importedPage = targetDoc.importPage(pagesToInsert.get(i));
                // importPage는 끝에 추가하므로 나중에 재배치
            }

            reorderInsertedPages(targetDoc, pagesAfterDelete, pagesToInsert.size(), insertPosition);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            targetDoc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * PDF에서 특정 페이지들을 추출하여 새 PDF로 반환한다.
     *
     * @param pdfData      원본 PDF 바이트 배열
     * @param pageNumbers  추출할 페이지 번호 목록 (1-based)
     * @return 추출된 페이지로 구성된 새 PDF 바이트 배열
     */
    public byte[] extractPages(byte[] pdfData, List<Integer> pageNumbers) throws IOException {
        try (PDDocument sourceDoc = Loader.loadPDF(pdfData);
             PDDocument newDoc = new PDDocument()) {
            int totalPages = sourceDoc.getNumberOfPages();
            validatePageNumbers(pageNumbers, totalPages);

            for (int pageNum : pageNumbers) {
                PDPage page = sourceDoc.getPages().get(pageNum - 1);
                newDoc.importPage(page);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            newDoc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * PDF의 총 페이지 수를 반환한다.
     */
    public int getPageCount(byte[] pdfData) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfData)) {
            return document.getNumberOfPages();
        }
    }

    private void reorderInsertedPages(PDDocument doc, int originalCount, int insertedCount, int insertIndex) {
        // importPage가 끝에 추가한 페이지들을 올바른 위치로 이동
        // 현재 상태: [원본 0..insertIndex-1] [원본 insertIndex..originalCount-1] [삽입된 페이지들]
        // 목표 상태: [원본 0..insertIndex-1] [삽입된 페이지들] [원본 insertIndex..originalCount-1]

        List<PDPage> allPages = new ArrayList<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            allPages.add(doc.getPages().get(i));
        }

        List<PDPage> reordered = new ArrayList<>();
        // insertIndex 이전의 원본 페이지
        for (int i = 0; i < insertIndex && i < originalCount; i++) {
            reordered.add(allPages.get(i));
        }
        // 삽입된 페이지들 (끝에 있음)
        for (int i = originalCount; i < allPages.size(); i++) {
            reordered.add(allPages.get(i));
        }
        // insertIndex 이후의 원본 페이지
        for (int i = insertIndex; i < originalCount; i++) {
            reordered.add(allPages.get(i));
        }

        // 기존 페이지 모두 제거 후 재배치된 순서로 추가
        while (doc.getNumberOfPages() > 0) {
            doc.removePage(0);
        }
        for (PDPage page : reordered) {
            doc.addPage(page);
        }
    }

    private void validatePageNumbers(List<Integer> pageNumbers, int totalPages) {
        if (pageNumbers == null || pageNumbers.isEmpty()) {
            throw new IllegalArgumentException("페이지 번호 목록이 비어있습니다.");
        }
        for (int pageNum : pageNumbers) {
            if (pageNum < 1 || pageNum > totalPages) {
                throw new IllegalArgumentException(
                        "페이지 번호는 1에서 " + totalPages + " 사이여야 합니다. 입력값: " + pageNum);
            }
        }
    }
}
