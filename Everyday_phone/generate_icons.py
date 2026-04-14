"""
Generate Android launcher icons for Everyday phone and glasses apps.

Requirements: pip install Pillow

Usage: python generate_icons.py [path_to_source_image]
"""

from PIL import Image
import os
import sys

# Android mipmap sizes
MIPMAP_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# Target directories
TARGETS = [
    r"C:\Users\jean-\Desktop\AugmentedReality\x3Pro\Everyday_phone\app\src\main\res",
    r"C:\Users\jean-\Desktop\AugmentedReality\x3Pro\Everyday_glasses\app\src\main\res",
]

def generate_icons(source_path):
    # Load source image
    print(f"Loading source image: {source_path}")
    
    if not os.path.exists(source_path):
        print(f"ERROR: Source image not found at {source_path}")
        return False
    
    img = Image.open(source_path)
    
    # Convert to RGBA if needed (for PNG transparency support)
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    
    print(f"Source image size: {img.size}")
    
    for target_res_dir in TARGETS:
        print(f"\nProcessing: {target_res_dir}")
        
        for mipmap_dir, size in MIPMAP_SIZES.items():
            output_dir = os.path.join(target_res_dir, mipmap_dir)
            
            # Create directory if it doesn't exist
            os.makedirs(output_dir, exist_ok=True)
            
            # Resize image using high-quality resampling
            resized = img.resize((size, size), Image.Resampling.LANCZOS)
            
            # Save as ic_launcher.png (standard Android launcher icon name)
            output_path = os.path.join(output_dir, "ic_launcher.png")
            resized.save(output_path, "PNG")
            print(f"  Created: {mipmap_dir}/ic_launcher.png ({size}x{size})")
            
            # Also create ic_launcher_round.png (for devices that use round icons)
            output_path_round = os.path.join(output_dir, "ic_launcher_round.png")
            resized.save(output_path_round, "PNG")
            print(f"  Created: {mipmap_dir}/ic_launcher_round.png ({size}x{size})")
    
    print("\n✓ All icons generated successfully!")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python generate_icons.py <path_to_source_image>")
        print("Example: python generate_icons.py C:\\Users\\jean-\\Downloads\\everyday_icon.png")
        sys.exit(1)
    
    source_path = sys.argv[1]
    success = generate_icons(source_path)
    sys.exit(0 if success else 1)
