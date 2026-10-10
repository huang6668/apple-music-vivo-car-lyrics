#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class P0ClassesTest(unittest.TestCase):
    def test_syntax_balance(self):
        for filename in ['CatalogTitleResolver.java', 'TitleCacheStore.java', 'TitleCorrectionState.java', 'ChineseConverter.java', 'TitleCacheStoreTest.java', 'TitleCorrectionStateTest.java', 'ChineseConverterTest.java']:
            if filename.endswith('Test.java'):
                p = ROOT / 'cloud-patch/tests/com/apple/android/music/player' / filename
            else:
                p = ROOT / 'cloud-patch/java/com/apple/android/music/player' / filename
            text = p.read_text(encoding='utf-8')
            self.assertEqual(text.count('{'), text.count('}'), f'{filename} brace mismatch')
            self.assertEqual(text.count('('), text.count(')'), f'{filename} paren mismatch')

    def test_catalog_title_resolver_batch_methods(self):
        ctr = (ROOT / 'cloud-patch/java/com/apple/android/music/player/CatalogTitleResolver.java').read_text(encoding='utf-8')
        self.assertIn('batchSongRequest(', ctr)
        self.assertIn('batchQuery(', ctr)
        self.assertIn('extractTitles(', ctr)
        self.assertIn('BatchCallback', ctr)
        self.assertIn('BatchOnce', ctr)

    def test_title_cache_store_batch_methods(self):
        tcs = (ROOT / 'cloud-patch/java/com/apple/android/music/player/TitleCacheStore.java').read_text(encoding='utf-8')
        self.assertIn('getAll(', tcs)
        self.assertIn('putAll(', tcs)
        self.assertIn('MAX_ENTRIES = 4096', tcs)

    def test_title_correction_state_batch_methods(self):
        tstate = (ROOT / 'cloud-patch/java/com/apple/android/music/player/TitleCorrectionState.java').read_text(encoding='utf-8')
        self.assertIn('DEFAULT_CACHE_SIZE = 4096', tstate)
        self.assertIn('getCachedTitle(', tstate)
        self.assertIn('putCachedTitle(', tstate)
        self.assertIn('putCachedTitles(', tstate)

    def test_chinese_converter_methods(self):
        cc = (ROOT / 'cloud-patch/java/com/apple/android/music/player/ChineseConverter.java').read_text(encoding='utf-8')
        self.assertIn('public static String toSimplified(', cc)
        self.assertIn('Arrays.binarySearch(', cc)
        self.assertIn('TRAD =', cc)
        self.assertIn('SIMP =', cc)

    def test_multi_region_fallback_methods(self):
        ctr = (ROOT / 'cloud-patch/java/com/apple/android/music/player/CatalogTitleResolver.java').read_text(encoding='utf-8')
        self.assertIn('regionalStorefrontHeader(', ctr)
        self.assertIn('executeBatchStage(', ctr)
        vcl = (ROOT / 'cloud-patch/java/com/apple/android/music/player/VivoCarLyrics.java').read_text(encoding='utf-8')
        self.assertIn('fallbackRegionalTitle(', vcl)
        self.assertIn('ChineseConverter.toSimplified(', vcl)


if __name__ == '__main__':
    unittest.main()
