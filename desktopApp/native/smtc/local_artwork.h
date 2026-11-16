#pragma once

#include <windows.h>
#include <string>
#include <vector>

namespace cosmotune_smtc {

inline bool file_artwork(const std::wstring& uri) {
    return uri.size() >= 5 && _wcsnicmp(uri.c_str(), L"file:", 5) == 0;
}

// Resolve from the system directory, not the current directory or the app's DLL search path.
inline HMODULE system_library(const wchar_t* name) {
    wchar_t directory[MAX_PATH]{};
    const UINT length = GetSystemDirectoryW(directory, MAX_PATH);
    if (length == 0 || length >= MAX_PATH) return nullptr;
    return LoadLibraryW((std::wstring(directory, length) + L"\\" + name).c_str());
}

// shcore's desktop API exists since Windows 8. Loading its documented ABI dynamically also works with
// MinGW versions that have WinRT interfaces but no shcore declaration/import library.
inline HRESULT local_artwork_stream(const std::wstring& uri, REFIID iid, void** result) {
    if (result == nullptr) return E_POINTER;
    *result = nullptr;
    if (!file_artwork(uri) || uri.size() > 32768 || uri.find_first_of(L"?#") != std::wstring::npos ||
        uri.find(L'\0') != std::wstring::npos || uri.find(L"%00") != std::wstring::npos) return E_INVALIDARG;
    using DecodePath = HRESULT (WINAPI*)(PCWSTR, PWSTR, DWORD*, DWORD);
    using OpenStream = HRESULT (WINAPI*)(PCWSTR, DWORD, REFIID, void**);
    static HMODULE shell = system_library(L"shlwapi.dll");
    static HMODULE core = system_library(L"shcore.dll");
    static auto decode = shell ? reinterpret_cast<DecodePath>(GetProcAddress(shell, "PathCreateFromUrlW")) : nullptr;
    static auto open = core ? reinterpret_cast<OpenStream>(GetProcAddress(core, "CreateRandomAccessStreamOnFile")) : nullptr;
    if (decode == nullptr || open == nullptr) return E_NOTIMPL;
    std::vector<wchar_t> buffer(32768, L'\0');
    DWORD length = static_cast<DWORD>(buffer.size());
    const HRESULT decoded = decode(uri.c_str(), buffer.data(), &length, 0);
    if (FAILED(decoded)) return decoded;
    const std::wstring path(buffer.data());
    // Chapter images are local files. Refuse UNC/device/relative paths rather than opening network shares.
    if (path.size() < 3 || !((path[0] >= L'A' && path[0] <= L'Z') || (path[0] >= L'a' && path[0] <= L'z')) ||
        path[1] != L':' || (path[2] != L'\\' && path[2] != L'/')) return E_INVALIDARG;
    return open(path.c_str(), 0 /* Windows.Storage.FileAccessMode.Read */, iid, result);
}

} // namespace cosmotune_smtc
