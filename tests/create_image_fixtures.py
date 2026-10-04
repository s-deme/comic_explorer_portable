"""Original color patterns for the Android 'images' suite. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw

out = Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets/image-formats'
out.mkdir(exist_ok=True)
image = Image.new('RGB', (96, 64), 'red')
ImageDraw.Draw(image).rectangle((48, 0, 95, 63), fill='blue')
image.save(out / 'baseline.jpg', quality=95)
image.save(out / 'progressive.jpeg', quality=95, progressive=True)
image.convert('CMYK').save(out / 'cmyk.jpg', quality=95)
for orientation in range(1, 9):
    exif = Image.Exif()
    exif[274] = orientation
    image.save(out / f'orientation-{orientation}.jpg', quality=95, exif=exif)
exif[274] = 6
image.save(out / 'orientation-6.png', exif=exif)
image.save(out / 'orientation-6.webp', lossless=True, exif=exif)
image.save(out / 'rgb.png')
image.convert('P', palette=Image.Palette.ADAPTIVE).save(out / 'indexed.png')
image.convert('L').save(out / 'gray.png')
image.convert('L').convert('I;16').save(out / 'gray16.png')
rgba = image.convert('RGBA')
rgba.putalpha(128)
rgba.save(out / 'alpha.png')
rgba.save(out / 'alpha.webp', lossless=True)
image.save(out / 'rgb.bmp')
image.convert('P', palette=Image.Palette.ADAPTIVE).save(out / 'indexed.bmp')
image.convert('1').save(out / 'mono.bmp')
image.save(out / 'still.gif')
image.save(out / 'lossy.webp', quality=95)
image.save(out / 'lossless.webp', lossless=True)
image.save(out / 'animated.webp', save_all=True,
           append_images=[Image.new('RGB', image.size, 'green')], duration=100, loop=0, lossless=True)
for extension in ('png', 'jpg'):
    image.resize((97, 65)).save(out / f'odd.{extension}')
