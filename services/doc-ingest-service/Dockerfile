FROM eclipse-temurin:21-jre
# tesseract-ocr-jpn / -jpn-vert add Japanese (horizontal + vertical) OCR for the
# Tika fallback path. The Docling sidecar does its own OCR; these cover scanned
# documents that route through Tika instead.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tesseract-ocr tesseract-ocr-jpn tesseract-ocr-jpn-vert \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java","-jar","/app/app.jar"]
