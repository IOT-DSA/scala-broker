#!/usr/bin/env bash
TARGET="${HOME}/.system/system"
if [ -d "${TARGET}" ]
then
  echo -n "DSA System already exists. Overwrite? (y/n) "
  read RESULT
  if [ "$RESULT" != "y" ]
  then
    exit 1
  fi
  rm -rf "${TARGET}"
fi
mkdir -p "${TARGET}"
git clone https://github.com/IOT-DSA/dslink-dart-system.git "${TARGET}"
pushd "${TARGET}" > /dev/null
pub get
popd > /dev/null
mkdir -p "${HOME}/bin"
cat > "${HOME}/bin/system" <<EOF
#!/usr/bin/env bash
dart $HOME/.system/system/bin/run.dart \$@
exit \$?
EOF
chmod +x "${HOME}/bin/system"
echo "Success!"