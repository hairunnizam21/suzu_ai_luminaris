FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless unzip wget curl git ca-certificates sqlite3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/apktool
RUN wget -q -O apktool.jar \
    https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.9.3.jar \
    && printf '#!/bin/bash\nexec java -jar /opt/apktool/apktool.jar "$@"\n' > /usr/local/bin/apktool \
    && chmod +x /usr/local/bin/apktool

WORKDIR /app
COPY pyproject.toml .env.example ./
COPY suzu_luminaris ./suzu_luminaris
RUN pip install --no-cache-dir -e .

EXPOSE 8765
CMD ["uvicorn", "suzu_luminaris.main:app", "--host", "0.0.0.0", "--port", "8765"]
