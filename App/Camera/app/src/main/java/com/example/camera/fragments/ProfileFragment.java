package com.example.camera.fragments;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.example.camera.R;
import com.example.camera.databinding.FragmentFriendsBinding;
import com.example.camera.databinding.FragmentProfileBinding;


public class ProfileFragment extends Fragment {


    private static final String TAG = "ProfileFragment";
    private FragmentProfileBinding _views;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        _views = FragmentProfileBinding.inflate(inflater, container, false);
        return _views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);


    }



}