// ParquetService.java
package com.processor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processor.model.ProcessingResponse;
import com.processor.util.FileProcessingUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.avro.generic.GenericRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ParquetService {
    @Autowired
    private FileProcessingUtils fileProcessingUtils;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ProcessingResponse> jobStatus = new ConcurrentHashMap<>();
    
    @Value("${app.output.directory:output}")
    private String outputDirectory;

    // Method to process files from URL
    @Async
    public String processParquetFromUrl(String fileUrl) throws IOException {
        String jobId = UUID.randomUUID().toString();
        jobStatus.put(jobId, new ProcessingResponse(jobId, "Started URL Processing"));
        
        try {
            // Download and process the zip file
            updateStatus(jobId, "Downloading from URL", 0.2);
            File zipFile = fileProcessingUtils.downloadFile(fileUrl);
            return processDownloadedFile(zipFile, jobId);
        } catch (Exception e) {
            handleProcessingError(jobId, e);
            throw e;
        }
    }

    // Method to process files from local directory
    @Async
    public String processParquetFromDirectory(String directoryPath) throws IOException {
        String jobId = UUID.randomUUID().toString();
        jobStatus.put(jobId, new ProcessingResponse(jobId, "Started Directory Processing"));
        
        try {
            File directory = new File(directoryPath);
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IOException("Invalid directory path: " + directoryPath);
            }

            updateStatus(jobId, "Scanning Directory", 0.2);
            List<File> parquetFiles = fileProcessingUtils.findParquetFiles(directory);
            
            if (parquetFiles.isEmpty()) {
                updateStatus(jobId, "No parquet files found", -1.0);
                return jobId;
            }

            processParquetFiles(parquetFiles, jobId);
            updateStatus(jobId, "Completed", 1.0);
            return jobId;
            
        } catch (Exception e) {
            handleProcessingError(jobId, e);
            throw e;
        }
    }

    // Helper method to process downloaded zip file
    private String processDownloadedFile(File zipFile, String jobId) throws IOException {
        try {
            // Extract the zip file
            updateStatus(jobId, "Extracting Files", 0.4);
            File extractedDir = fileProcessingUtils.extractZip(zipFile);
            
            // Find and process parquet files
            updateStatus(jobId, "Processing Parquet Files", 0.6);
            List<File> parquetFiles = fileProcessingUtils.findParquetFiles(extractedDir);
            
            if (parquetFiles.isEmpty()) {
                updateStatus(jobId, "No parquet files found in zip", -1.0);
                return jobId;
            }

            processParquetFiles(parquetFiles, jobId);
            
            // Cleanup temporary files
            updateStatus(jobId, "Cleaning up", 0.8);
            fileProcessingUtils.cleanup(zipFile, extractedDir);
            
            updateStatus(jobId, "Completed", 1.0);
            return jobId;
            
        } catch (Exception e) {
            handleProcessingError(jobId, e);
            throw e;
        }
    }

    // Core method to process parquet files
    private void processParquetFiles(List<File> parquetFiles, String jobId) throws IOException {
        // Create output directory if it doesn't exist
        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        double progressIncrement = 0.4 / parquetFiles.size();
        double currentProgress = 0.4; // Starting from 40% progress

        for (File parquetFile : parquetFiles) {
            try {
                processParquetFile(parquetFile, jobId, outputDir);
                currentProgress += progressIncrement;
                updateStatus(jobId, "Processing file: " + parquetFile.getName(), currentProgress);
            } catch (Exception e) {
                log.error("Error processing file {}: {}", parquetFile.getName(), e.getMessage());
                // Continue processing other files even if one fails
            }
        }
    }

    // Process individual parquet file
    private void processParquetFile(File parquetFile, String jobId, File outputDir) throws IOException {
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
            
            // Generate output filename
            String jsonFileName = parquetFile.getName().replace(".parquet", ".json");
            File outputFile = new File(outputDir, jsonFileName);
            
            // Write JSON with pretty printing
            objectMapper.writerWithDefaultPrettyPrinter()
                       .writeValue(outputFile, records);
            
            log.info("Successfully processed file: {} to {}", 
                    parquetFile.getName(), outputFile.getPath());
        }
    }

    // Convert GenericRecord to Map
    private Map<String, Object> convertToMap(GenericRecord record) {
        Map<String, Object> map = new HashMap<>();
        record.getSchema().getFields().forEach(field -> {
            String fieldName = field.name();
            Object value = record.get(fieldName);
            
            // Handle nested records
            if (value instanceof GenericRecord) {
                value = convertToMap((GenericRecord) value);
            } else if (value instanceof List) {
                value = handleListValues((List<?>) value);
            }
            
            map.put(fieldName, value);
        });
        return map;
    }

    // Handle list values in records
    private List<Object> handleListValues(List<?> list) {
        List<Object> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof GenericRecord) {
                result.add(convertToMap((GenericRecord) item));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    // Error handling helper method
    private void handleProcessingError(String jobId, Exception e) {
        log.error("Processing error for job {}: {}", jobId, e.getMessage());
        updateStatus(jobId, "Failed: " + e.getMessage(), -1.0);
    }

    // Status update helper method
    private void updateStatus(String jobId, String message, Double progress) {
        ProcessingResponse response = new ProcessingResponse(jobId, "PROCESSING");
        response.setMessage(message);
        response.setProgress(progress);
        if (progress >= 1.0) {
            response.setStatus("COMPLETED");
        } else if (progress < 0) {
            response.setStatus("FAILED");
        }
        jobStatus.put(jobId, response);
        log.info("Job {}: {}", jobId, message);
    }

    // Get processing status
    public ProcessingResponse getStatus(String jobId) {
        return jobStatus.getOrDefault(jobId, 
            new ProcessingResponse(jobId, "Job not found"));
    }
}
