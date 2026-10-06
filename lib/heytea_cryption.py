import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad
from hashlib import md5

def encrypt_heytea_mobile(mobile: str) -> str:
    """
    按喜茶的方式加密手机号码
    
    Args:
        mobile: 手机号码字符串
        
    Returns:
        加密后的Base64字符串，失败返回None
    """
    try:
        # 喜茶的固定密钥和IV
        key = b"23290CFFBB5D39B8"
        iv = b"HEYTEA1A2B3C4D5E"
        
        # 转换为字节并填充
        from Crypto.Util.Padding import pad
        mobile_bytes = mobile.encode('utf-8')
        padded_data = pad(mobile_bytes, AES.block_size)
        
        # AES-CBC加密
        cipher = AES.new(key, AES.MODE_CBC, iv)
        encrypted = cipher.encrypt(padded_data)
        
        # Base64编码
        return base64.b64encode(encrypted).decode('utf-8')
        
    except Exception as e:
        print(f"加密失败: {e}")
        return None
    
def timestamp_sign(user_main_id:int, timestamp:int) -> str:
    """
    生成时间戳签名
    
    Args:
        user_main_id: 用户主ID
        timestamp: 时间戳（秒）
        
    Returns:
        签名字符串
    """
    salt = "r5YWPjgSGAT2dbOJzwiDBK"
    sign_str = f"{salt}{user_main_id}{timestamp}"

    return md5(sign_str.encode('utf-8')).hexdigest()

def decrypt_response_data(encrypted_response: str, is_app: bool = False) -> str:
    """
    解密服务器响应数据
    
    Args:
        encrypted_response: 带前缀的加密响应数据
        is_app: 是否为APP版本 (默认为小程序版本)
        
    Returns:
        解密后的JSON字符串
    """

    # 响应数据解密的密钥 (根据平台不同)
    response_key_app = b"F61niK84bDQAsVHy"      # APP版本
    response_key_weapp = b"ByOCfgNpMRKtwWhJ"    # 小程序版本
    encryption_prefix = "HEYTEA_ENCRYPTION_TRANSMISSION"
    iv = b"HEYTEA1A2B3C4D5E"
    block_size = AES.block_size

    try:
        # 检查是否有加密前缀
        if not encrypted_response.startswith(encryption_prefix):
            return encrypted_response  # 未加密，直接返回
        
        # 去除前缀
        encrypted_data = encrypted_response[len(encryption_prefix):]
        
        # 选择对应的密钥
        response_key = response_key_app if is_app else response_key_weapp
        
        # Base64解码
        encrypted_bytes = base64.b64decode(encrypted_data)
        
        # AES-CBC解密
        cipher = AES.new(response_key, AES.MODE_CBC, iv)
        decrypted_padded = cipher.decrypt(encrypted_bytes)
        
        # 去除PKCS7填充
        decrypted = unpad(decrypted_padded, block_size)
        
        # 转换为字符串
        return decrypted.decode('utf-8')
        
    except Exception as e:
        print(f"响应数据解密失败: {e}")
        return None