// ParquetProcessorApplication.java
package com.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ParquetProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(ParquetProcessorApplication.class, args);
    }
}

// Config/AsyncConfig.java
package com.processor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ParquetThread-");
        executor.initialize();
        return executor;
    }
}

// Controller/ParquetController.java
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
    
    @PostMapping("/process")
    public ResponseEntity<ProcessingResponse> processParquetFile(
            @RequestParam("url") String fileUrl) {
        try {
            String jobId = parquetService.processParquetFromUrl(fileUrl);
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

// Model/ProcessingResponse.java
package com.processor.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProcessingResponse {
    private String jobId;
    private String status;
    private String message;
    private Double progress;
    
    public ProcessingResponse(String jobId, String message) {
        this.jobId = jobId;
        this.message = message;
        this.status = "PROCESSING";
        this.progress = 0.0;
    }
}

// Service/ParquetService.java
package com.processor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processor.model.ProcessingResponse;
import com.processor.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.avro.generic.GenericRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ParquetService {
    @Autowired
    private FileUtils fileUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ProcessingResponse> jobStatus = new ConcurrentHashMap<>();
    
    @Async
    public String processParquetFromUrl(String fileUrl) throws IOException {
        String jobId = UUID.randomUUID().toString();
        jobStatus.put(jobId, new ProcessingResponse(jobId, "Started"));
        
        try {
            // Download file
            updateStatus(jobId, "Downloading", 0.2);
            File zipFile = fileUtils.downloadFile(fileUrl);
            
            // Extract zip
            updateStatus(jobId, "Extracting", 0.4);
            File extractedDir = fileUtils.extractZip(zipFile);
            
            // Process parquet files
            updateStatus(jobId, "Processing Parquet", 0.6);
            List<File> parquetFiles = fileUtils.findParquetFiles(extractedDir);
            
            for (File parquetFile : parquetFiles) {
                processParquetFile(parquetFile, jobId);
            }
            
            // Cleanup
            updateStatus(jobId, "Cleaning up", 0.8);
            fileUtils.cleanup(zipFile, extractedDir);
            
            updateStatus(jobId, "Completed", 1.0);
            return jobId;
            
        } catch (Exception e) {
            log.error("Error processing file: ", e);
            updateStatus(jobId, "Failed: " + e.getMessage(), -1.0);
            throw e;
        }
    }
    
    private void processParquetFile(File parquetFile, String jobId) throws IOException {
        Configuration conf = new Configuration();
        Path path = new Path(parquetFile.getAbsolutePath());
        
        try (ParquetReader<GenericRecord> reader = AvroParquetReader
                .<GenericRecord>builder(path)
                .withConf(conf)
                .build()) {
            
            List<Map<String, Object>> records = new ArrayList<>();
            GenericRecord record;
            
            while ((record = reader.read()) != null) {
                Map<String, Object> recordMap = convertToMap(record);
                records.add(recordMap);
            }
            
            // Write to JSON
            String jsonFileName = parquetFile.getName().replace(".parquet", ".json");
            objectMapper.writeValue(new File(jsonFileName), records);
        }
    }
    
    private Map<String, Object> convertToMap(GenericRecord record) {
        Map<String, Object> map = new HashMap<>();
        record.getSchema().getFields().forEach(field -> {
            String fieldName = field.name();
            Object value = record.get(fieldName);
            map.put(fieldName, value);
        });
        return map;
    }
    
    private void updateStatus(String jobId, String message, Double progress) {
        ProcessingResponse response = new ProcessingResponse(jobId, "PROCESSING");
        response.setMessage(message);
        response.setProgress(progress);
        jobStatus.put(jobId, response);
    }
    
    public ProcessingResponse getStatus(String jobId) {
        return jobStatus.getOrDefault(jobId, 
            new ProcessingResponse(jobId, "Job not found"));
    }
}

// Util/FileUtils.java
package com.processor.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@Slf4j
public class FileUtils {
    public File downloadFile(String fileUrl) throws IOException {
        URL url = new URL(fileUrl);
        File tempFile = File.createTempFile("download", ".zip");
        
        try (InputStream in = url.openStream();
             FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        
        return tempFile;
    }
    
    public File extractZip(File zipFile) throws IOException {
        File extractDir = Files.createTempDirectory("extract").toFile();
        
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            
            while ((entry = zis.getNextEntry()) != null) {
                File entryFile = new File(extractDir, entry.getName());
                
                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                    continue;
                }
                
                File parent = entryFile.getParentFile();
                if (!parent.exists()) {
                    parent.mkdirs();
                }
                
                try (FileOutputStream fos = new FileOutputStream(entryFile)) {
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
        }
        
        return extractDir;
    }
    
    public List<File> findParquetFiles(File directory) {
        List<File> parquetFiles = new ArrayList<>();
        if (!directory.exists()) return parquetFiles;
        
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    parquetFiles.addAll(findParquetFiles(file));
                } else if (file.getName().endsWith(".parquet")) {
                    parquetFiles.add(file);
                }
            }
        }
        
        return parquetFiles;
    }
    
    public void cleanup(File... files) {
        for (File file : files) {
            try {
                if (file.isDirectory()) {
                    FileUtils.deleteDirectory(file);
                } else {
                    file.delete();
                }
            } catch (IOException e) {
                log.error("Error cleaning up file: " + file.getPath(), e);
            }
        }
    }
}

// application.properties
server.port=8080
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB
logging.level.com.processor=DEBUG
