import os

from dotenv import load_dotenv
from openai import OpenAI

load_dotenv()

api_key = os.getenv("OPENAI_API_KEY")
model = os.getenv("OPENAI_MODEL", "gpt-6-luna")

if not api_key:
    raise RuntimeError("OPENAI_API_KEY를 찾을 수 없습니다.")

client = OpenAI(api_key=api_key)

response = client.responses.create(
    model=model,
    reasoning={"effort": "none"},
    input = "Reply with excatly: API connection successful",
    max_output_tokens=20,
    store = False,
)

print(response.output_text)