"""Extract text, tables, and basic metadata from supported offline document formats."""

import io
from pathlib import Path

from fastapi import UploadFile

from app.models import ParsedDocument


class DocumentParseError(RuntimeError):
    """Raised when a file is unsupported, malformed, or needs an unavailable local parser."""


class DocumentParser:
    """Provider-neutral parser for text, PDF, Office, and image inputs."""

    async def parse(self, upload: UploadFile) -> ParsedDocument:
        """Read a bounded upload and dispatch by extension without executing embedded content."""
        payload = await upload.read(100 * 1024 * 1024 + 1)
        if len(payload) > 100 * 1024 * 1024:
            raise DocumentParseError("File exceeds 100 MB limit")
        suffix = Path(upload.filename or "").suffix.lower()
        media_type = upload.content_type or "application/octet-stream"
        if suffix in {".txt", ".md", ".markdown"}:
            return ParsedDocument(media_type=media_type, text=self._decode_text(payload))
        if suffix == ".pdf":
            return self._parse_pdf(payload, media_type)
        if suffix == ".docx":
            return self._parse_docx(payload, media_type)
        if suffix == ".xlsx":
            return self._parse_xlsx(payload, media_type)
        if suffix == ".pptx":
            return self._parse_pptx(payload, media_type)
        if suffix in {".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp"}:
            return self._parse_image(payload, media_type)
        raise DocumentParseError(f"Unsupported file type: {suffix or 'unknown'}")

    def _decode_text(self, payload: bytes) -> str:
        """Decode common enterprise text encodings with deterministic fallback order."""
        for encoding in ("utf-8-sig", "gb18030", "utf-16"):
            try:
                return payload.decode(encoding)
            except UnicodeDecodeError:
                continue
        raise DocumentParseError("Text encoding is not supported")

    def _parse_pdf(self, payload: bytes, media_type: str) -> ParsedDocument:
        """Extract ordered page text from a PDF without running JavaScript or attachments."""
        try:
            from pypdf import PdfReader
            reader = PdfReader(io.BytesIO(payload))
            text = "\n\n".join(page.extract_text() or "" for page in reader.pages)
            return ParsedDocument(media_type=media_type, text=text, page_count=len(reader.pages))
        except ImportError as exc:
            raise DocumentParseError("PDF parser is not installed") from exc
        except Exception as exc:
            raise DocumentParseError(f"PDF parsing failed: {exc}") from exc

    def _parse_docx(self, payload: bytes, media_type: str) -> ParsedDocument:
        """Extract paragraphs and tables from a DOCX package."""
        try:
            from docx import Document
            document = Document(io.BytesIO(payload))
            tables = [[[cell.text for cell in row.cells] for row in table.rows] for table in document.tables]
            text = "\n".join(paragraph.text for paragraph in document.paragraphs if paragraph.text)
            return ParsedDocument(media_type=media_type, text=text, tables=tables)
        except ImportError as exc:
            raise DocumentParseError("Word parser is not installed") from exc
        except Exception as exc:
            raise DocumentParseError(f"Word parsing failed: {exc}") from exc

    def _parse_xlsx(self, payload: bytes, media_type: str) -> ParsedDocument:
        """Read calculated cell values from each workbook sheet without evaluating macros."""
        try:
            from openpyxl import load_workbook
            workbook = load_workbook(io.BytesIO(payload), read_only=True, data_only=True)
            tables: list[list[list[str]]] = []
            text_parts: list[str] = []
            for sheet in workbook.worksheets:
                rows = [["" if value is None else str(value) for value in row]
                        for row in sheet.iter_rows(values_only=True)]
                tables.append(rows)
                text_parts.append(f"[{sheet.title}]\n" + "\n".join("\t".join(row) for row in rows))
            return ParsedDocument(media_type=media_type, text="\n\n".join(text_parts), tables=tables)
        except ImportError as exc:
            raise DocumentParseError("Excel parser is not installed") from exc
        except Exception as exc:
            raise DocumentParseError(f"Excel parsing failed: {exc}") from exc

    def _parse_pptx(self, payload: bytes, media_type: str) -> ParsedDocument:
        """Extract visible text in slide order from a PPTX package."""
        try:
            from pptx import Presentation
            presentation = Presentation(io.BytesIO(payload))
            slides = ["\n".join(shape.text for shape in slide.shapes if hasattr(shape, "text"))
                      for slide in presentation.slides]
            return ParsedDocument(
                media_type=media_type, text="\n\n".join(slides), page_count=len(slides)
            )
        except ImportError as exc:
            raise DocumentParseError("PowerPoint parser is not installed") from exc
        except Exception as exc:
            raise DocumentParseError(f"PowerPoint parsing failed: {exc}") from exc

    def _parse_image(self, payload: bytes, media_type: str) -> ParsedDocument:
        """Run local OCR when Pillow and Tesseract bindings are available."""
        try:
            from PIL import Image
            import pytesseract
            text = pytesseract.image_to_string(Image.open(io.BytesIO(payload)), lang="chi_sim+eng")
            return ParsedDocument(media_type=media_type, text=text)
        except ImportError as exc:
            raise DocumentParseError("OCR dependencies are not installed") from exc
        except Exception as exc:
            raise DocumentParseError(f"Image OCR failed: {exc}") from exc
