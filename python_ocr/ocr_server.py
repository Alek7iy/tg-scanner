import io
import uvicorn
from fastapi import FastAPI, File, UploadFile
from PIL import Image
import numpy as np
from rapidocr import RapidOCR, LangRec, OCRVersion, ModelType

app = FastAPI()

engine = RapidOCR(
    params={
        "Rec.ocr_version": OCRVersion.PPOCRV5,
        "Rec.lang_type": LangRec.CYRILLIC,
        "Rec.model_type": ModelType.MOBILE,
    }
)

@app.post("/ocr")
async def perform_ocr(file: UploadFile = File(...)):
    contents = await file.read()
    image = Image.open(io.BytesIO(contents)).convert("RGB")
    img_np = np.array(image)

    result = engine(img_np)

    # В rapidocr 3.x найденные строки лежат в result.txts
    if result and getattr(result, "txts", None):
        extracted_text = "\n".join(result.txts)
    else:
        extracted_text = ""

    return {"text": extracted_text}


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8000)