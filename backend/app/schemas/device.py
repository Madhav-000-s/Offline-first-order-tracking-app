from pydantic import BaseModel, Field


class DeviceIn(BaseModel):
    fcm_token: str = Field(min_length=1, max_length=512)
    platform: str = Field(default="android", max_length=20)
