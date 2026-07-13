"""Tests for bounded offline document parsing and normalization."""

import pytest
from fastapi import UploadFile

from app.documents import DocumentParseError, DocumentParser


@pytest.mark.asyncio
async def test_parses_utf8_markdown() -> None:
    """Markdown must remain plain extracted text for later chunking and indexing."""
    parser = DocumentParser()
    upload = UploadFile(filename="guide.md", file=__import__("io").BytesIO("# 标题\n正文".encode()))

    result = await parser.parse(upload)

    assert result.text == "# 标题\n正文"


@pytest.mark.asyncio
async def test_rejects_unsupported_executable() -> None:
    """Executable uploads must not enter the parsing pipeline."""
    parser = DocumentParser()
    upload = UploadFile(filename="tool.exe", file=__import__("io").BytesIO(b"MZ"))

    with pytest.raises(DocumentParseError, match="Unsupported"):
        await parser.parse(upload)


@pytest.mark.asyncio
async def test_legacy_word_is_handled_by_backend() -> None:
    """Binary Office files are parsed by the backend Apache POI pipeline."""
    parser = DocumentParser()
    upload = UploadFile(filename="legacy.doc", file=__import__("io").BytesIO(b"old-office"))

    with pytest.raises(DocumentParseError, match="backend Apache POI"):
        await parser.parse(upload)


def test_scanned_pdf_falls_back_to_ocr(monkeypatch: pytest.MonkeyPatch) -> None:
    """A PDF with pages but no extractable text should be rendered and OCRed locally."""
    from pypdf import PdfWriter
    buffer = __import__("io").BytesIO()
    writer = PdfWriter()
    writer.add_blank_page(width=100, height=100)
    writer.write(buffer)
    parser = DocumentParser()
    monkeypatch.setattr(parser, "_ocr_pdf", lambda payload: "扫描文字")

    result = parser._parse_pdf(buffer.getvalue(), "application/pdf")

    assert result.text == "扫描文字"
    assert result.page_count == 1
