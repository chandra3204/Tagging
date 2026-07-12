import sys
import json
import cv2
import os

# Disable PaddlePaddle log messages to keep stdout clean for JSON parsing
os.environ["PPOCR_LOG_LEVEL"] = "WARNING"
# Disable oneDNN/MKLDNN to prevent fused_conv2d CPU runtime crash on Windows
os.environ["FLAGS_use_onednn"] = "0"
os.environ["FLAGS_use_mkldnn"] = "0"

try:
    from paddleocr import PaddleOCR
except ImportError:
    # If paddleocr fails to import globally, add system path or fallback
    pass

def main():
    if len(sys.argv) < 6:
        print(json.dumps({"error": "Missing arguments. Usage: python paddle_ocr_runner.py <image_path> <x> <y> <w> <h>"}))
        return

    img_path = sys.argv[1]
    try:
        x = int(float(sys.argv[2]))
        y = int(float(sys.argv[3]))
        w = int(float(sys.argv[4]))
        h = int(float(sys.argv[5]))
    except ValueError:
        print(json.dumps({"error": "Invalid coordinates"}))
        return

    # Normalize coordinates in case width/height are negative
    if w < 0:
        x = x + w
        w = -w
    if h < 0:
        y = y + h
        h = -h

    # Load image
    img = cv2.imread(img_path)
    if img is None:
        print(json.dumps({"error": f"Failed to load image: {img_path}"}))
        return

    # Crop image with minor padding
    padding = 10
    img_h, img_w, _ = img.shape
    x_start = max(0, x - padding)
    y_start = max(0, y - padding)
    x_end = min(img_w, x + w + padding)
    y_end = min(img_h, y + h + padding)

    cropped = img[y_start:y_end, x_start:x_end]
    if cropped.size == 0:
        print(json.dumps({"error": "Empty crop region"}))
        return

    # Initialize PaddleOCR
    # lang='en' for English models, show_log=False to suppress print statements
    try:
        ocr = PaddleOCR(use_angle_cls=True, lang='en', show_log=False, enable_mkldnn=False)
        result = ocr.ocr(cropped, cls=True)
    except Exception as e:
        print(json.dumps({"error": f"PaddleOCR Initialization or Inference failed: {str(e)}"}))
        return

    # Parse results
    detected_lines = []
    total_confidence = 0.0
    word_count = 0

    if result and result[0]:
        for line in result[0]:
            # line format: [ [ [x1,y1], ... ], (text, confidence) ]
            box_coords, (text, conf) = line
            detected_lines.append(text)
            total_confidence += conf
            word_count += 1

    # Calculate average confidence (%)
    avg_confidence = (total_confidence / word_count * 100.0) if word_count > 0 else 0.0
    full_text = "\n".join(detected_lines)

    print(json.dumps({
        "detectedText": full_text,
        "confidence": avg_confidence
    }))

if __name__ == "__main__":
    main()
