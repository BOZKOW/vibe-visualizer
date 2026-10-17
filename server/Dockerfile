# Server helper: Spicy Lyrics proxy (secret key stays here), per-device preferences, and the
# optional unofficial Spotify Canvas lookup (headless Chromium via Playwright).
FROM mcr.microsoft.com/playwright/python:v1.62.0-noble
RUN pip install --no-cache-dir --break-system-packages playwright==1.62.0
WORKDIR /app
COPY helper.py /app/helper.py
CMD ["python3", "-u", "/app/helper.py"]
