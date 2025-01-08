package com.processor.controller;

import com.processor.model.ProcessingResponse;
import com.processor.service.ParquetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/parquet")
public class ParquetController {
    @Autowired
    private ParquetService parquetService;
    
    @PostMapping("/process/url")
    public ResponseEntity<ProcessingResponse> processParquetFromUrl(
            @RequestParam("url") String fileUrl) {
        try {
            String jobId = parquetService.processParquetFromUrl(fileUrl);
            return ResponseEntity.ok(new ProcessingResponse(jobId, "Processing Started"));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                .body(new ProcessingResponse(null, "Error: " + e.getMessage()));
        }
    }
    
    @PostMapping("/process/directory")
    public ResponseEntity<ProcessingResponse> processParquetFromDirectory(
            @RequestParam("path") String directoryPath) {
        try {
            String jobId = parquetService.processParquetFromDirectory(directoryPath);
            return ResponseEntity.ok(new ProcessingResponse(jobId, "Processing Started"));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                .body(new ProcessingResponse(null, "Error: " + e.getMessage()));
        }
    }
    
    @GetMapping("/status/{jobId}")
    public ResponseEntity<ProcessingResponse> getStatus(@PathVariable String jobId) {
        return ResponseEntity.ok(parquetService.getStatus(jobId));
    }
}
