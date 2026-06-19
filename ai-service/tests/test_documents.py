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
