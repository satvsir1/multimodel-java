// File structure:
pom.xml
src/main/java/com/processor/
    ParquetProcessorApplication.java
    controller/ParquetController.java
    service/ParquetService.java
    config/AsyncConfig.java
    model/ProcessingResponse.java
    util/FileUtils.java

// pom.xml dependencies
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    
    <groupId>com.processor</groupId>
    <artifactId>parquet-processor</artifactId>
    <version>1.0.0</version>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.7.0</version>
    </parent>
    
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.parquet</groupId>
            <artifactId>parquet-hadoop</artifactId>
            <version>1.12.3</version>
        </dependency>
        <dependency>
            <groupId>org.apache.hadoop</groupId>
            <artifactId>hadoop-common</artifactId>
            <version>3.3.4</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.13.3</version>
        </dependency>
    </dependencies>
</project>

// Main Application
@SpringBootApplication
public class ParquetProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(ParquetProcessorApplication.class, args);
    }
}

// Controller
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

// Service
@Service
@Slf4j
public class ParquetService {
    @Autowired
    private FileUtils fileUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Async
    public String processParquetFromUrl(String fileUrl) throws IOException {
        String jobId = UUID.randomUUID().toString();
        
        // Download and extract zip
        File zipFile = fileUtils.downloadFile(fileUrl);
        File extractedDir = fileUtils.extractZip(zipFile);
        
        // Process all parquet files
        List<File> parquetFiles = fileUtils.findParquetFiles(extractedDir);
        for (File parquetFile : parquetFiles) {
            processParquetFile(parquetFile, jobId);
        }
        
        // Cleanup
        fileUtils.cleanup(zipFile, extractedDir);
        return jobId;
    }
    
    private void processParquetFile(File parquetFile, String jobId) throws IOException {
        Configuration conf = new Configuration();
        Path path = new Path(parquetFile.getAbsolutePath());
        
        try (ParquetReader<GenericRecord> reader = ParquetReader.builder(new GenericRecordReadSupport(), path)
                .withConf(conf)
                .build()) {
            
            GenericRecord record;
            List<Map<String, Object>> records = new ArrayList<>();
            
            while ((record = reader.read()) != null) {
                Map<String, Object> recordMap = convertToMap(record);
                records.add(recordMap);
            }
            
            // Write to JSON file
            String jsonFileName = parquetFile.getName().replace(".parquet", ".json");
            objectMapper.writeValue(new File(jsonFileName), records);
        }
    }
    
    private Map<String, Object> convertToMap(GenericRecord record) {
        Map<String, Object> map = new HashMap<>();
        for (Schema.Field field : record.getSchema().getFields()) {
            String fieldName = field.name();
            Object value = record.get(fieldName);
            map.put(fieldName, value);
        }
        return map;
    }
}

// File Utils
@Component
public class FileUtils {
    public File downloadFile(String fileUrl) throws IOException {
        URL url = new URL(fileUrl);
        File tempFile = File.createTempFile("download", ".zip");
        
        try (InputStream in = url.openStream();
             FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
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
            while ((entry = zis.getNextEntry()) != null) {
                File entryFile = new File(extractDir, entry.getName());
                
                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                } else {
                    try (FileOutputStream fos = new FileOutputStream(entryFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
        
        return extractDir;
    }
    
    public List<File> findParquetFiles(File directory) {
        List<File> parquetFiles = new ArrayList<>();
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
            if (file.isDirectory()) {
                FileUtils.deleteDirectory(file);
            } else {
                file.delete();
            }
        }
    }
}
