// Optional Windows native smoke: real file-URI decoding, WinRT file stream and stream reference creation.
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <roapi.h>
#include <winstring.h>
#define ____FIReference_1_BYTE_INTERFACE_DEFINED__
#include <windows.foundation.h>
#include <windows.storage.streams.h>
#include <cstdio>
#include "local_artwork.h"

using namespace ABI::Windows::Storage::Streams;

int main() {
    if (FAILED(RoInitialize(RO_INIT_MULTITHREADED))) return 1;
    wchar_t directory[MAX_PATH]{}, temporary[MAX_PATH]{};
    if (!GetTempPathW(MAX_PATH, directory) || !GetTempFileNameW(directory, L"csm", 0, temporary)) return 2;
    DeleteFileW(temporary);
    const std::wstring path = std::wstring(temporary) + L" chapter \u00e9.png";
    HANDLE file = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW, FILE_ATTRIBUTE_TEMPORARY, nullptr);
    if (file == INVALID_HANDLE_VALUE) return 3;
    const unsigned char png[] = {137, 80, 78, 71, 13, 10, 26, 10};
    DWORD written = 0;
    const bool saved = WriteFile(file, png, sizeof(png), &written, nullptr) && written == sizeof(png);
    CloseHandle(file);
    std::wstring uri = L"file:///";
    for (wchar_t ch : path) {
        if (ch == L'\\') uri += L'/';
        else if (ch == L' ') uri += L"%20";
        else uri += ch;
    }
    IRandomAccessStream* stream = nullptr;
    IRandomAccessStreamReferenceStatics* factory = nullptr;
    IRandomAccessStreamReference* reference = nullptr;
    HSTRING name = nullptr;
    const wchar_t* class_name = RuntimeClass_Windows_Storage_Streams_RandomAccessStreamReference;
    WindowsCreateString(class_name, static_cast<UINT32>(wcslen(class_name)), &name);
    UINT64 size = 0;
    const bool passed = saved &&
        SUCCEEDED(cosmotune_smtc::local_artwork_stream(uri, __uuidof(IRandomAccessStream), reinterpret_cast<void**>(&stream))) &&
        SUCCEEDED(stream->get_Size(&size)) && size == sizeof(png) &&
        SUCCEEDED(RoGetActivationFactory(name, __uuidof(IRandomAccessStreamReferenceStatics), reinterpret_cast<void**>(&factory))) &&
        SUCCEEDED(factory->CreateFromStream(stream, &reference)) && reference != nullptr;
    if (reference) reference->Release();
    if (factory) factory->Release();
    if (stream) stream->Release();
    WindowsDeleteString(name);
    IRandomAccessStream* refused = nullptr;
    const bool safe = FAILED(cosmotune_smtc::local_artwork_stream(L"file://untrusted.example/share/art.png", __uuidof(IRandomAccessStream), reinterpret_cast<void**>(&refused))) &&
        FAILED(cosmotune_smtc::local_artwork_stream(L"https://example.com/art.png", __uuidof(IRandomAccessStream), reinterpret_cast<void**>(&refused)));
    DeleteFileW(path.c_str());
    RoUninitialize();
    std::puts(passed && safe ? "Local SMTC artwork stream passed" : "Local SMTC artwork stream failed");
    return passed && safe ? 0 : 4;
}
