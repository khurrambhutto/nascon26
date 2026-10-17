FROM python:3.12-slim
WORKDIR /srv
COPY requirements-linux.lock install_dependencies.py ./
RUN --mount=type=secret,id=wheel_mirror python install_dependencies.py
COPY app ./app
EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
