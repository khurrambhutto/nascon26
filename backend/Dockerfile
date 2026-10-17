FROM python:3.12-slim
WORKDIR /srv
COPY requirements.txt .
COPY constraints.txt .
RUN pip install --no-cache-dir --retries 5 --timeout 30 -r requirements.txt -c constraints.txt
COPY app ./app
EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
