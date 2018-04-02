#!/usr/bin/env bash
TARGET="${HOME}/.weather/weather"
if [ -d "${TARGET}" ]
then
  echo -n "DSA Weather already exists. Overwrite? (y/n) "
  read RESULT
  if [ "$RESULT" != "y" ]
  then
    exit 1
  fi
  rm -rf "${TARGET}"
fi
mkdir -p "${TARGET}"
git clone https://github.com/IOT-DSA/dslink-dart-weather.git "${TARGET}"
pushd "${TARGET}" > /dev/null
pub get
popd > /dev/null
mkdir -p "${HOME}/bin"
cat > "${HOME}/bin/weather" <<EOF
#!/usr/bin/env bash
dart $HOME/.weather/weather/bin/run.dart \$@
exit \$?
EOF
chmod +x "${HOME}/bin/weather"
echo "Success!"